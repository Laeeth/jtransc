package com.jtransc.ast.optimize

import com.jtransc.ast.*
import com.jtransc.lang.toBool
import com.jtransc.types.Locals

//const val DEBUG = false
//const val DEBUG = true

object AstOptimizer : AstVisitor() {
	private var stm: AstStm? = null

	override fun visit(stm: AstStm?) {
		super.visit(stm)
		this.stm = stm
	}

	val METHODS_TO_STRIP = setOf<AstMethodRef>(
		AstMethodRef("kotlin.jvm.internal.Intrinsics".fqname, "checkParameterIsNotNull", AstType.METHOD(AstType.VOID, listOf(AstType.OBJECT, AstType.STRING)))
	)

	override fun visit(expr: AstExpr.BINOP) {
		super.visit(expr)
		val box = expr.box
		val left = expr.left.value
		val right = expr.right.value
		when (expr.op) {
			AstBinop.EQ, AstBinop.NE -> {
				if ((left is AstExpr.CAST) && (right is AstExpr.LITERAL)) {
					if (left.from == AstType.BOOL && left.to == AstType.INT && right.value is Int) {
						//println("optimize")
						val leftExpr = left.expr.value
						val toZero = right.value == 0
						val equals = expr.op == AstBinop.EQ

						box.value = if (toZero xor equals) leftExpr else AstExpr.UNOP(AstUnop.NOT, leftExpr)
						AstAnnotateExpressions.visitExprWithStm(stm, box)
					}
				}
			}
		}
	}

	override fun visit(expr: AstExpr.CALL_STATIC) {
		super.visit(expr)

		if (expr.method in METHODS_TO_STRIP) {
			expr.stm?.box?.value = AstStm.NOP("method to strip")
		}
	}

	override fun visit(body: AstBody) {
		// @TODO: this should be easier when having the SSA form
		for (local in body.locals) {
			if (local.writes.size == 1) {
				val write = local.writes[0]
				var writeExpr2 = write.expr.value
				while (writeExpr2 is AstExpr.CAST) writeExpr2 = writeExpr2.expr.value
				val writeExpr = writeExpr2
				//println("Single write: $local = $writeExpr")
				when (writeExpr) {
					is AstExpr.PARAM, is AstExpr.THIS -> { // LITERALS!
						for (read in local.reads) {
							//println("  :: read: $read")
							read.box.value = write.expr.value.clone()
						}
						write.box.value = AstStm.NOP("optimized literal")
						local.writes.clear()
						local.reads.clear()
					}
				}
				//println("Written once! $local")
			}
		}

		super.visit(body)

		// REMOVE UNUSED VARIABLES
		body.locals = body.locals.filter { it.isUsed }
	}

	override fun visit(stms: AstStm.STMS) {
		super.visit(stms)

		for (n in 1 until stms.stms.size) {
			val abox = stms.stms[n - 1]
			val bbox = stms.stms[n - 0]
			val a = abox.value
			val b = bbox.value
			//println("${a.javaClass}")

			if (a is AstStm.SET_LOCAL && b is AstStm.SET_LOCAL) {
				val alocal = a.local.local
				val blocal = b.local.local
				val aexpr = a.expr.value
				val bexpr = b.expr.value
				if (aexpr is AstExpr.LOCAL && bexpr is AstExpr.LOCAL) {
					//println("double set locals! $alocal = ${aexpr.local} :: ${blocal} == ${bexpr.local}")
					if ((alocal == bexpr.local) && (aexpr.local == blocal)) {
						//println("LOCAL[a]:" + alocal)

						blocal.writes.remove(b)
						alocal.reads.remove(bexpr)

						val aold = a
						abox.value = AstStm.NOP("optimized set local")
						bbox.value = aold

						//println("LOCAL[b]:" + alocal)
						//println("double set! CROSS!")
					}
				}
			}

			if (a is AstStm.SET_LOCAL && a.expr.value is AstExpr.LOCAL) {
				//val blocal = a.expr.value as AstExpr.LOCAL
				val alocal = a.local.local
				if (alocal.writesCount == 1 && alocal.readCount == 1 && alocal.reads.first().stm == b) {
					alocal.reads.first().box.value = a.expr.value
					abox.value = AstStm.NOP("optimized set local 2")
					alocal.writes.clear()
					alocal.reads.clear()
				}
			}
		}

		val finalStms = stms.stms.filter { it.value !is AstStm.NOP }

		if (finalStms.size == 1) {
			stms.box.value = finalStms.first().value
		}
	}

	override fun visit(expr: AstExpr.UNOP) {
		super.visit(expr)
		if (expr.op == AstUnop.NOT) {
			val right = expr.right.value
			when (right) {
				is AstExpr.BINOP -> {
					var newop = when (right.op) {
						AstBinop.NE -> AstBinop.EQ
						AstBinop.EQ -> AstBinop.NE
						AstBinop.LT -> AstBinop.GE
						AstBinop.LE -> AstBinop.GT
						AstBinop.GT -> AstBinop.LE
						AstBinop.GE -> AstBinop.LT
						else -> null
					}
					if (newop != null) expr.box.value = AstExpr.BINOP(right.type, right.left.value, newop, right.right.value)
				}
				is AstExpr.UNOP -> {
					if (right.op == AstUnop.NOT) {
						// negate twice!
						expr.box.value = right.right.value
					}
				}
			}
		}
	}

	override fun visit(stm: AstStm.SET_LOCAL) {
		super.visit(stm)
		val box = stm.box
		val expr = stm.expr.value
		val storeLocal = stm.local.local

		if (expr is AstExpr.LOCAL) {
			// FIX: Assigning a value to itself
			if (storeLocal == expr.local) {
				storeLocal.writes.remove(stm)
				storeLocal.reads.remove(expr)
				box.value = AstStm.NOP("assign to itself")
				return
			}
		}

		// Do not assign and remove variables that are not going to be used!
		if (storeLocal.readCount == 0 && storeLocal.writesCount == 1) {
			box.value = AstStm.STM_EXPR(stm.expr.value)
			storeLocal.writes.clear()
			visit(box)
			return
		}

		// Dummy cast
		if (expr is AstExpr.CAST && stm.local.type == expr.from) {
			val exprBox = expr.box
			exprBox.value = expr.expr.value
			AstAnnotateExpressions.visitExprWithStm(stm, exprBox)
			return
		}
	}

	override fun visit(expr: AstExpr.CAST) {
		super.visit(expr)

		val castTo = expr.to
		val child = expr.expr.value

		val box = expr.box

		//println("${expr.expr.type} -> ${expr.to}")

		// DUMMY CAST
		if (expr.expr.type == castTo) {
			box.value = expr.expr.value
			visit(box)
			return
		}

		// DOUBLE CAST
		if (child is AstExpr.CAST) {
			val cast1 = expr
			val cast2 = child
			if ((cast1.type is AstType.REF) && (cast2.type is AstType.REF)) {
				cast1.expr.value = cast2.expr.value
				visit(box)
			}
			return
		}

		// CAST LITERAL
		if (child is AstExpr.LITERAL) {
			val literalValue = child.value
			if (literalValue is Int) {
				val box = expr.box
				when (castTo) {
					AstType.BOOL -> box.value = AstExpr.LITERAL(literalValue.toBool())
					AstType.BYTE -> box.value = AstExpr.LITERAL(literalValue.toByte())
					AstType.SHORT -> box.value = AstExpr.LITERAL(literalValue.toShort())
					//AstType.CHAR -> expr.box.value = AstExpr.LITERAL(literalValue.toChar())
				}
				AstAnnotateExpressions.visitExprWithStm(stm, box)
				return
			}
		}
	}

	override fun visit(stm: AstStm.IF) {
		super.visit(stm)
		val strue = stm.strue.value
		if (strue is AstStm.IF) {
			val cond = AstExpr.BINOP(AstType.BOOL, stm.cond.value, AstBinop.BAND, strue.cond.value)
			stm.box.value = AstStm.IF(cond, strue.strue.value)
		}
	}

	override fun visit(stm: AstStm.IF_ELSE) {
		super.visit(stm)
		val cond = stm.cond.value
		val strue = stm.strue.value
		val sfalse = stm.sfalse.value
		if ((strue is AstStm.SET_LOCAL) && (sfalse is AstStm.SET_LOCAL) && (strue.local.local == sfalse.local.local)) {
			val local = strue.local
			// TERNARY OPERATOR
			//println("ternary!")
			local.local.writes.remove(strue)
			local.local.writes.remove(sfalse)

			val newset = AstStm.SET_LOCAL(local, AstExpr.TERNARY(cond, strue.expr.value, sfalse.expr.value))
			stm.box.value = newset
			local.local.writes.add(newset)
		}
	}

	override fun visit(stm: AstStm.STM_EXPR) {
		if (stm.expr.value.isPure()) {
			stm.box.value = AstStm.NOP("pure stm")
		}
	}
}

fun AstExpr.Box.isPure(): Boolean = this.value.isPure()

fun AstExpr.isPure(): Boolean = when (this) {
	is AstExpr.ARRAY_ACCESS -> this.array.isPure() && this.index.isPure() // Can cause null pointer/out of bounds
	is AstExpr.ARRAY_LENGTH -> true // Can cause null pointer
	is AstExpr.BINOP -> this.left.isPure() && this.right.isPure()
	is AstExpr.UNOP -> this.right.isPure()
	is AstExpr.CALL_BASE -> false // we would have to check call pureness
	is AstExpr.CAST -> this.expr.isPure()
	is AstExpr.FIELD_INSTANCE_ACCESS -> this.expr.isPure()
	is AstExpr.INSTANCE_OF -> this.expr.isPure()
	is AstExpr.TERNARY -> this.cond.isPure() && this.etrue.isPure() && this.efalse.isPure()
	is AstExpr.CAUGHT_EXCEPTION -> true
	is AstExpr.FIELD_STATIC_ACCESS -> true
	is AstExpr.LITERAL -> true
	is AstExpr.LOCAL -> true
	is AstExpr.NEW -> false
	is AstExpr.NEW_WITH_CONSTRUCTOR -> false
	is AstExpr.NEW_ARRAY -> true
	is AstExpr.PARAM -> true
	is AstExpr.THIS -> true
	else -> {
		println("Warning: Unhandled expr $this to check pureness")
		false
	}
}

object AstAnnotateExpressions : AstVisitor() {
	private var stm: AstStm? = null

	fun visitExprWithStm(stm: AstStm?, box: AstExpr.Box) {
		this.stm = stm
		visit(box)
	}

	override fun visit(stm: AstStm?) {
		this.stm = stm
		super.visit(stm)
	}

	override fun visit(expr: AstExpr?) {
		expr?.stm = stm
		super.visit(expr)
	}
}

fun AstBody.optimize() = this.apply {
	AstAnnotateExpressions.visit(this)
	AstOptimizer.visit(this)
}
fun AstStm.Box.optimize() = this.apply {
	AstAnnotateExpressions.visit(this)
	AstOptimizer.visit(this)
}
fun AstExpr.Box.optimize() = this.apply {
	AstAnnotateExpressions.visit(this)
	AstOptimizer.visit(this)
}

fun AstStm.optimize() = this.let { this.box.optimize().value }
fun AstExpr.optimize() = this.let { this.box.optimize().value }