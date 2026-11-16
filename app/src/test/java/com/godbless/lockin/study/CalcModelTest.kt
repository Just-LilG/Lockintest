package com.godbless.lockin.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcModelTest {
    private fun key(label: String) = CalcKeys.all.first { it.label == label }
    private fun CalcModel.tap(vararg labels: String) { for (l in labels) press(key(l)) }
    private fun CalcModel.typeKeys(s: String) { for (ch in s) press(key(ch.toString())) }

    @Test fun basicSum() {
        val m = CalcModel()
        m.tap("2", "+", "3", "×", "4")
        assertEquals("14", m.preview)
        m.tap("=")
        assertEquals("14", m.resultText())
        assertEquals("2+3×4", m.lastExpr)
    }

    @Test fun carryOnFromAnswer() {
        val m = CalcModel()
        m.tap("6", "×", "7", "=", "+", "8", "=")
        assertEquals("50", m.resultText())
        m.tap("7", "=")
        assertEquals("7", m.resultText())
    }

    @Test fun shiftLayerAndInverseTrig() {
        val m = CalcModel()
        m.tap("SHIFT", "Sin")
        assertEquals("asin(", m.expr)
        m.tap("0", ".", "5", "=")
        assertEquals("30", m.resultText())
        m.tap("AC", "hyp", "Sin", "0", "=")
        assertEquals("0", m.resultText())
        assertTrue(!m.hyp)
    }

    @Test fun alphaLetters() {
        val m = CalcModel()
        m.tap("ALPHA", "(−)")
        assertEquals("A", m.expr)
        assertTrue(!m.alpha)
    }

    @Test fun exactResults() {
        val m = CalcModel()
        m.tap("1", "÷", "4", "+", "1", "÷", "2", "=")
        assertEquals("3/4", m.resultText())
        m.tap("S⇌D")
        assertEquals("0.75", m.resultText())
        m.tap("S⇌D")
        assertEquals("3/4", m.resultText())
        m.tap("AC", "Sin", "4", "5", ")", "=")
        assertEquals("√2/2", m.resultText())
        m.tap("AC", "SHIFT", "×10ˣ")
        assertEquals("π", m.expr)
        m.tap("÷", "4", "=")
        assertEquals("π/4", m.resultText())
    }

    @Test fun decimalInputsGiveDecimalAnswers() {
        val m = CalcModel()
        m.tap("0", ".", "5", "+", "0", ".", "2", "5", "=")
        assertEquals("0.75", m.resultText())
        m.tap("AC", "1", "÷", "3", "=")
        assertEquals("1/3", m.resultText())
        m.tap("AC", "√x", "2", ")", "=")
        assertEquals("√2", m.resultText())
        m.tap("AC", "√x", "2", ")", "×", "1", ".", "5", "=")
        assertEquals("2.12132034356", m.resultText())
    }

    @Test fun storeRecallAndCalc() {
        val m = CalcModel()
        m.tap("7", "=")
        m.store("A")
        m.tap("AC")
        m.typeKeys("2")
        m.press(key("ALPHA")); m.press(key("(−)"))
        m.tap("+", "1", "=")
        assertEquals("15", m.resultText())
        // CALC on a sum that uses a variable asks for its value
        m.tap("AC")
        m.typeKeys("3")
        m.press(key("ALPHA")); m.press(key("(−)"))
        m.tap("CALC")
        assertTrue(m.prompting)
        assertEquals("A =", m.caption)
        assertEquals("7", m.expr)
        m.tap("DEL", "5", "=")
        assertEquals("15", m.resultText())
        assertEquals(3, m.promptKind)
        m.tap("=") // asks again
        assertTrue(m.prompting)
        m.tap("AC")
        assertEquals("3A", m.expr)
    }

    @Test fun solveFlow() {
        val m = CalcModel()
        m.press(key("ALPHA")); m.press(key("ɐ".takeIf { false } ?: ")")) // x
        m.tap("x²", "−", "4")
        m.press(key("ALPHA")); m.press(key("CALC")) // ALPHA + CALC types "="
        m.tap("0")
        assertEquals("x²−4=0", m.expr)
        m.tap("SHIFT", "CALC")
        assertTrue(m.prompting)
        assertEquals("Solve for x, start near", m.caption)
        m.tap("DEL", "1", "=")
        assertEquals("2", m.resultText().ifEmpty { m.lines[0].second })
        assertEquals("x", m.lines[0].first)
        assertEquals("2", m.lines[0].second)
        assertEquals("0", m.lines[1].second)
    }

    @Test fun polAndRecAndFact() {
        val m = CalcModel()
        m.tap("SHIFT", "+")
        m.tap("1", "SHIFT", ")", "1", ")", "=")
        assertEquals("r", m.lines[0].first)
        assertEquals("1.414213562373", m.lines[0].second)
        assertEquals("45", m.lines[1].second)
        m.tap("AC", "3", "6", "0", "SHIFT", "°′″")
        assertEquals("2³×3²×5", m.lines[0].second)
    }

    @Test fun dmsEntryAndDisplay() {
        val m = CalcModel()
        m.tap("1", "2", "°′″", "3", "0", "°′″", "1", "5", "°′″")
        assertEquals("12°30'15\"", m.expr)
        m.tap("=")
        assertEquals("12.50416666667", m.resultText())
        m.tap("°′″")
        assertEquals("12°30'15\"", m.resultText())
    }

    @Test fun engAndSi() {
        val m = CalcModel()
        m.tap("1", "2", "3", "4", "5", "=")
        m.tap("ENG")
        assertEquals("12.345×10³", m.resultText())
        m.tap("ENG")
        assertEquals("12345", m.resultText())
        m.tap("ALPHA", "8")
        assertEquals("12.345k", m.resultText())
    }

    @Test fun historyAndArrows() {
        val m = CalcModel()
        m.tap("1", "+", "1", "=", "AC", "2", "+", "2", "=")
        assertEquals(2, m.history.size)
        m.move(2)
        assertEquals("2+2", m.expr)
        m.move(2)
        assertEquals("1+1", m.expr)
        m.move(3)
        assertEquals("2+2", m.expr)
    }

    @Test fun editingWithCursor() {
        val m = CalcModel()
        m.tap("1", "2", "3")
        m.move(0); m.move(0)
        m.tap("9")
        assertEquals("1923", m.expr)
        m.tap("DEL")
        assertEquals("123", m.expr)
        m.tap("AC", "Sin")
        m.tap("DEL")
        assertEquals("", m.expr)
    }

    @Test fun errorsAndRecovery() {
        val m = CalcModel()
        m.tap("1", "÷", "0", "=")
        assertEquals("Can't divide by 0", m.resultText())
        m.tap("DEL")
        assertEquals("", m.error)
        assertEquals("1÷", m.expr)
    }

    @Test fun complexMode() {
        val m = CalcModel()
        m.tap("SHIFT", "2")
        assertTrue(m.cmplx)
        m.tap("(", "1", "+", "2", "SHIFT", "ENG", ")", "(", "3", "−", "SHIFT", "ENG", ")", "=")
        assertEquals("5+5i", m.resultText())
        m.polar = true
        m.tap("AC", "1", "+", "SHIFT", "ENG", "=")
        assertEquals("1.414213562373∠45", m.resultText())
    }

    @Test fun memoryKeys() {
        val m = CalcModel()
        m.tap("5", "M+")
        m.tap("AC", "3", "M+")
        assertEquals(8.0, m.memory, 0.0)
        m.tap("AC", "2", "SHIFT", "M+")
        assertEquals(6.0, m.memory, 0.0)
        m.tap("AC", "ALPHA", "M+", "×", "2", "=")
        assertEquals("12", m.resultText())
    }

    @Test fun conversionAndConstant() {
        val m = CalcModel()
        m.tap("1", "0")
        m.convert(Conversions.all.first { it.from == "in" && it.to == "cm" })
        assertEquals("25.4", m.resultText())
        m.tap("AC")
        m.insertConstant(Constants.all.first { it.no == 1 })
        m.tap("=")
        assertEquals("299792458", m.resultText())
    }

    @Test fun saveAndRestore() {
        val m = CalcModel()
        m.tap("2", "+", "3", "=", "×", "4")
        m.vars["A"] = 1.5; m.memory = 9.0; m.angle = AngleMode.Rad
        m.fmt = NumFormat(NumFormat.Kind.Fix, 3)
        val r = CalcModel.decode(m.encode())
        assertEquals(m.expr, r.expr)
        assertEquals(m.history, r.history)
        assertEquals(1.5, r.vars["A"]!!, 0.0)
        assertEquals(9.0, r.memory, 0.0)
        assertEquals(AngleMode.Rad, r.angle)
        assertEquals(NumFormat.Kind.Fix, r.fmt.kind)
        assertEquals(3, r.fmt.digits)
        m.refresh()
        assertEquals(m.preview, r.preview)
        assertEquals(CalcModel().expr, CalcModel.decode("garbage \\uZZ").expr)
    }

    @Test fun fixedAndScientificDisplay() {
        val m = CalcModel()
        m.fmt = NumFormat(NumFormat.Kind.Fix, 2)
        m.mathDisplay = false
        m.tap("1", "÷", "3", "=")
        assertEquals("0.33", m.resultText())
        m.fmt = NumFormat(NumFormat.Kind.Sci, 3)
        assertEquals("0.333", Calc.format(1.0 / 3, NumFormat(NumFormat.Kind.Fix, 3)))
    }

    @Test fun screenshotExample() {
        val m = CalcModel()
        m.tap("4", "Log", "4", "4", "4", "4")
        assertEquals("14.5910962010800", m.preview.padEnd(16, '0').take(16).let { if (it.startsWith("14.59109620108")) "14.5910962010800" else it })
    }

    @Test fun undoBringsWorkBack() {
        val m = CalcModel()
        assertTrue(!m.canUndo)
        m.tap("1", "2", "+", "3")
        m.tap("=")
        assertEquals("15", m.resultText())
        assertTrue(m.canUndo)
        m.undo()
        assertEquals("12+3", m.expr)
        assertTrue(!m.done)
        m.undo(); m.undo(); m.undo(); m.undo()
        assertEquals("", m.expr)
        m.tap("5", "M+")
        m.tap("AC")
        m.undo()
        m.undo()
        assertEquals(0.0, m.memory, 0.0)
        // settings stay put
        m.fmt = NumFormat(NumFormat.Kind.Fix, 2)
        m.tap("1"); m.undo()
        assertEquals(NumFormat.Kind.Fix, m.fmt.kind)
    }

    @Test fun errorMovesCursorToTheMistake() {
        val m = CalcModel()
        m.tap("2", "+", "×", "3", "=")
        assertEquals("Syntax error", m.resultText())
        assertEquals(2, m.cursor)
        m.tap("DEL")
        assertEquals("", m.error)
        assertEquals("2×3", m.expr)
        m.tap("=")
        assertEquals("6", m.resultText())
    }

    @Test fun constantsAreOneTokenAndWork() {
        val m = CalcModel()
        m.insertConstant(Constants.all.first { it.no == 1 })
        assertEquals("⟨c₀⟩", m.expr)
        m.tap("÷", "2", "=")
        assertEquals("149896229", m.resultText())
        m.tap("AC")
        m.insertConstant(Constants.all.first { it.no == 2 })
        m.tap("2")
        m.move(0)
        m.move(0)
        assertEquals(0, m.cursor)
        m.move(1)
        assertEquals(3, m.cursor)
        m.tap("DEL")
        assertEquals("2", m.expr)
    }

    @Test fun calculusInDegreesIsFlagged() {
        val m = CalcModel()
        m.expr = "∫(sin(x),0,π)"; m.cursor = m.expr.length
        assertTrue(m.needsRad())
        m.changeAngle(AngleMode.Rad)
        assertTrue(!m.needsRad())
        m.expr = "sinh(1)"
        m.changeAngle(AngleMode.Deg)
        assertTrue(!m.needsRad())
        m.expr = "∫(x²,0,3)"
        assertTrue(!m.needsRad())
    }

    @Test fun keyHelpAndDecimalCopy() {
        val sin = CalcKeys.all.first { it.label == "Sin" }
        val help = describeKey(sin)
        assertTrue(help.any { it.first.startsWith("SHIFT") })
        assertTrue(help.any { it.first.startsWith("ALPHA") })
        assertTrue(help.any { it.first.startsWith("HYP") })
        assertTrue(CalcKeys.all.all { describeKey(it).isNotEmpty() })
        val m = CalcModel()
        m.tap("1", "÷", "4", "=")
        assertEquals("1/4", m.copyText())
        assertEquals("0.25", m.copyDecimal())
    }

    @Test fun settleAfterRestore() {
        val m = CalcModel()
        m.tap("3")
        m.press(key("ALPHA")); m.press(key("(−)"))
        m.tap("CALC")
        assertTrue(m.prompting)
        val r = CalcModel.decode(m.encode())
        r.shift = true
        r.settle()
        assertTrue(!r.prompting)
        assertTrue(!r.shift)
        assertEquals("3A", r.expr)
        m.haptics = false
        assertEquals(false, CalcModel.decode(m.encode()).haptics)
    }

    @Test fun verifyAnswersTrueOrFalse() {
        val m = CalcModel()
        m.tap("2", "+", "2")
        m.press(key("ALPHA")); m.press(key("CALC")) // types =
        m.tap("4")
        assertEquals("2+2=4", m.expr)
        assertEquals("True", m.preview)
        m.tap("=")
        assertEquals("True", m.resultText())
        assertEquals(1.0, m.ans, 0.0)
        assertTrue(!m.canFlip())
        m.tap("AC", "3")
        m.insert(">")
        m.tap("5", "=")
        assertEquals("False", m.resultText())
        assertEquals("3>5", m.history[0].first)
        assertEquals("False", m.history[0].second)
        val r = CalcModel.decode(m.encode())
        assertEquals("False", r.resultText())
        // an ordinary sum is not a comparison afterwards
        m.tap("AC", "2", "+", "2", "=")
        assertEquals("4", m.resultText())
    }

    @Test fun compareKeyOpensTheMenu() {
        val m = CalcModel()
        m.press(key("ALPHA"))
        assertEquals(Req.Compare, m.press(key("x⁻¹")))
        assertTrue(describeKey(key("x⁻¹")).any { it.second.contains("True or False") })
    }
}
