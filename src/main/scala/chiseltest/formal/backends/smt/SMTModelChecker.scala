// SPDX-License-Identifier: Apache-2.0
// Author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package chiseltest.formal.backends.smt

import chiseltest.formal.backends._
import firrtl.backends.experimental.smt._

import scala.collection.mutable

case class SMTModelCheckerOptions(checkConstraints: Boolean, checkBadStatesIndividually: Boolean)
object SMTModelCheckerOptions {
  val Default: SMTModelCheckerOptions =
    SMTModelCheckerOptions(checkConstraints = true, checkBadStatesIndividually = true)
  val Performance: SMTModelCheckerOptions =
    SMTModelCheckerOptions(checkConstraints = false, checkBadStatesIndividually = false)
}

/** SMT based bounded model checking as an alternative to dispatching to a btor2 based external solver */
class SMTModelChecker(
  val solver:    Solver,
  options:       SMTModelCheckerOptions = SMTModelCheckerOptions.Performance,
  printProgress: Boolean = false)
    extends IsModelChecker {
  override val name:          String = "SMTModelChecker with " + solver.name
  override val prefix:        String = solver.name
  override val fileExtension: String = ".smt2"

  override def check(sys: TransitionSystem, kMax: Int, fileName: Option[String] = None): ModelCheckResult = {
    require(kMax > 0 && kMax <= 2000, s"unreasonable kMax=$kMax")
    if (fileName.nonEmpty) println("WARN: dumping to file is not supported at the moment.")

    val logic = if (solver.name == "z3") { "ALL" }
    else { "QF_AUFBV" }
    solver.setLogic(logic)

    // create new context
    solver.push()

    // declare/define functions and encode the transition system
    val enc = new CompactEncoding(sys)
    enc.defineHeader(solver)
    enc.init(solver)

    val constraints = sys.signals.filter(_.lbl == IsConstraint).map(_.name)
    val assertions = sys.signals.filter(_.lbl == IsBad).map(_.name)

    (0 to kMax).foreach { k =>
      if (printProgress) println(s"Step #$k")

      // assume all constraints hold in this step
      constraints.foreach(c => solver.assert(enc.getConstraint(c)))

      // make sure the constraints are not contradictory
      if (options.checkConstraints) {
        val res = solver.check(produceModel = false)
        assert(res.isSat, s"Found unsatisfiable constraints in cycle $k")
      }

      if (options.checkBadStatesIndividually) {
        // check each bad state individually
        assertions.zipWithIndex.foreach { case (b, bi) =>
          if (printProgress) print(s"- b$bi? ")

          solver.push()
          solver.assert(BVNot(enc.getAssertion(b)))
          val res = solver.check(produceModel = false)

          // did we find an assignment for which the bad state is true?
          if (res.isSat) {
            if (printProgress) println("❌")
            val w = getWitness(sys, enc, k, Seq(b))
            solver.pop()
            solver.pop()
            assert(solver.stackDepth == 0, s"Expected solver stack to be empty, not: ${solver.stackDepth}")
            return ModelCheckFail(w)
          } else {
            if (printProgress) println("✅")
          }
          solver.pop()
        }
      } else {
        val anyBad = BVNot(BVAnd(assertions.map(enc.getAssertion)))
        solver.push()
        solver.assert(anyBad)
        val res = solver.check(produceModel = false)

        // did we find an assignment for which at least one bad state is true?
        if (res.isSat) {
          val w = getWitness(sys, enc, k)
          solver.pop()
          solver.pop()
          assert(solver.stackDepth == 0, s"Expected solver stack to be empty, not: ${solver.stackDepth}")
          return ModelCheckFail(w)
        }
        solver.pop()
      }

      // advance
      enc.unroll(solver)
    }

    // clean up
    solver.pop()
    assert(solver.stackDepth == 0, s"Expected solver stack to be empty, not: ${solver.stackDepth}")
    ModelCheckSuccess()
  }

  private def getWitness(
    sys:             TransitionSystem,
    enc:             CompactEncoding,
    kMax:            Int,
    failedAssertion: Seq[String] = Seq()
  ): Witness = {
    // btor2 numbers states in the order that they are declared in starting at zero
    val stateInit = sys.states.zipWithIndex.map {
      case (State(sym: BVSymbol, _, _), ii) =>
        solver.getValue(enc.getSignalAt(sym, 0)) match {
          case Some(value) => (Some(ii -> value), None)
          case None        => (None, None)
        }
      case (State(sym: ArraySymbol, _, _), ii) =>
        val value = solver.getValue(enc.getSignalAt(sym, 0))
        (None, Some(ii -> value))
    }

    val regInit = stateInit.flatMap(_._1).toMap
    val memInit = stateInit.flatMap(_._2).toMap

    val inputs = (0 to kMax).map { k =>
      sys.inputs.zipWithIndex.flatMap { case (input, i) =>
        solver.getValue(enc.getSignalAt(input, k)).map(value => i -> value)
      }.toMap
    }

    Witness(failedAssertion, regInit, memInit, inputs)
  }

}

class CompactEncoding(sys: TransitionSystem) {
  import SMTTransitionSystemEncoder._
  private def id(s: String): String = SMTLibSerializer.escapeIdentifier(s)
  private val stateType = id(sys.name + "_s")
  private val stateInitFun = id(sys.name + "_i")
  private val stateTransitionFun = id(sys.name + "_t")

  private val states = mutable.ArrayBuffer[UTSymbol]()

  def defineHeader(solver: Solver): Unit = encode(sys).foreach(solver.runCommand)

  private def appendState(solver: Solver): UTSymbol = {
    val s = UTSymbol(s"s${states.length}", stateType)
    solver.runCommand(DeclareUninterpretedSymbol(s.name, s.tpe))
    states.append(s)
    s
  }

  def init(solver: Solver): Unit = {
    assert(states.isEmpty)
    val s0 = appendState(solver)
    solver.assert(BVFunctionCall(stateInitFun, List(s0), 1))
  }

  def unroll(solver: Solver): Unit = {
    assert(states.nonEmpty)
    appendState(solver)
    val tStates = states.takeRight(2).toList
    solver.assert(BVFunctionCall(stateTransitionFun, tStates, 1))
  }

  /** returns an expression representing the constraint in the current state */
  def getConstraint(name: String): BVExpr = {
    assert(states.nonEmpty)
    val foo = id(name + "_f")
    BVFunctionCall(foo, List(states.last), 1)
  }

  /** returns an expression representing the assertion in the current state */
  def getAssertion(name: String): BVExpr = {
    assert(states.nonEmpty)
    val foo = id(name + "_f")
    BVFunctionCall(foo, List(states.last), 1)
  }

  def getSignalAt(sym: BVSymbol, k: Int): BVExpr = {
    assert(states.length > k, s"no state s$k")
    val state = states(k)
    val foo = id(sym.name + "_f")
    BVFunctionCall(foo, List(state), sym.width)
  }

  def getSignalAt(sym: ArraySymbol, k: Int): ArrayExpr = {
    assert(states.length > k, s"no state s$k")
    val state = states(k)
    val foo = id(sym.name + "_f")
    ArrayFunctionCall(foo, List(state), sym.indexWidth, sym.dataWidth)
  }
}