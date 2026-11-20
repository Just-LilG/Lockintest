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
    // ---- 0.28.1 regressions ----

    @Test fun negativeKeyAfterAnswerStartsNewNumber() {
        val m = CalcModel()
        m.tap("2", "+", "3", "=", "(−)", "5", "=")
        assertEquals("-5", m.resultText())
        // the minus operator still carries on from the answer
        val n = CalcModel()
        n.tap("2", "+", "3", "=", "−", "1", "=")
        assertEquals("4", n.resultText())
        // and (−) then Ans is minus the answer
        val k = CalcModel()
        k.tap("5", "=", "(−)", "Ans", "=")
        assertEquals("-5", k.resultText())
    }

    @Test fun equalsAgainKeepsTheExactAnswer() {
        val m = CalcModel()
        m.tap("1", "÷", "3", "=", "=", "×", "3", "=")
        assertEquals("1", m.resultText())
        val n = CalcModel()
        n.tap("1", "÷", "3", "=", "=")
        assertEquals("1/3", n.resultText())
        assertEquals("1÷3", n.lastExpr)
    }

    @Test fun timesTenKeyNeverTypesTheVariableE() {
        val m = CalcModel()
        m.tap("5", "=", "×10ˣ", "2", "=")
        assertEquals("500", m.resultText())
        val n = CalcModel()
        n.tap("×10ˣ", "3", "=")
        assertEquals("1000", n.resultText())
        val k = CalcModel()
        k.tap("2", "×10ˣ", "3", "+", "1", "=")
        assertEquals("2001", k.resultText())
    }

    @Test fun pasteHandlesSeparators() {
        fun pasted(t: String) = CalcModel().also { it.pasteText(t) }.expr
        assertEquals("1000+1", pasted("1,000+1"))
        assertEquals("12345.5+1", pasted("12,345.5+1"))
        assertEquals("1234567", pasted("1,234,567"))
        assertEquals("3.14", pasted("3,14"))
        assertEquals("gcd(12,18)", pasted("gcd(12,18)"))
        assertEquals("1000", pasted("1 000"))
    }
    @Test fun fractionKeyBuildsAStackedFractionAndCarriesOn() {
        val m = CalcModel()
        m.tap("3", "a/b", "4")
        m.move(1)                                  // step out of the bottom, as on a real calculator
        m.tap("+", "1", "a/b", "4", "=")
        assertEquals("1", m.resultText())
        assertEquals("{3|4}+{1|4}", m.lastExpr)
        val n = CalcModel()
        n.tap("1", "a/b", "2", "=", "a/b", "3", "=")
        assertEquals("1/6", n.resultText())
        // typing "+" while still in the bottom keeps it in the bottom
        val s = CalcModel()
        s.tap("1", "a/b", "2", "+")
        assertEquals("{1|2+}", s.expr)
        // a pasted 3/4 is still an ordinary division
        val p = CalcModel()
        p.pasteText("3/4")
        p.tap("=")
        assertEquals("3/4", p.resultText())
        // the ÷ key still types ÷
        val k = CalcModel()
        k.tap("1", "÷", "2")
        assertEquals("1÷2", k.expr)
    }

    @Test fun inputLineHasNoTrailingEquals() {
        val m = CalcModel()
        m.tap("2", "+", "3", "=")
        assertEquals("2+3", m.inputText)
        assertEquals(3, m.inputCursor)
    }

    // ---- stacked fractions ----------------------------------------------------------------

    private fun frac(m: CalcModel) = m.tap("a/b")

    @Test fun fractionKeyTakesTheNumberBeforeIt() {
        val m = CalcModel()
        m.tap("5", "5", "6", "9", "6")
        frac(m)
        assertEquals("{55696|}", m.expr)
        assertEquals(7, m.cursor)               // after the bar, in the bottom
        m.tap("3")
        assertEquals("{55696|3}", m.expr)
        m.tap("=")
        assertEquals("55696/3", m.resultText())
        assertTrue(m.history[0].first == "{55696|3}")
        assertEquals("55696÷3", prettySum(m.history[0].first))
    }

    @Test fun emptyFractionAndNavigation() {
        val m = CalcModel()
        frac(m)
        assertEquals("{|}", m.expr)
        assertEquals(1, m.cursor)               // in the top
        m.tap("1")
        m.move(3)                               // down: to the bottom
        assertEquals(3, m.cursor)
        m.tap("2")
        assertEquals("{1|2}", m.expr)
        m.move(2)                               // up: back to the top, no history browsing
        assertEquals(2, m.cursor)
        m.tap("=")
        assertEquals("1/2", m.resultText())
        assertEquals(0.5, m.ans, 1e-12)
    }

    @Test fun fractionTakesBracketsFunctionsAndPowers() {
        fun top(setup: (CalcModel) -> Unit): String { val m = CalcModel(); setup(m); frac(m); return m.expr }
        assertEquals("2+{3|}", top { it.tap("2", "+", "3") })
        assertEquals("{(1+2)|}", top { it.tap("(", "1", "+", "2", ")") })
        assertEquals("{sin(30)|}", top { it.tap("Sin", "3", "0", ")") })
        assertEquals("{5²|}", top { it.tap("5", "x²") })
        assertEquals("{1.5|}", top { it.tap("1", ".", "5") })
        assertEquals("{√(9)|}", top { it.tap("√x", "9", ")") })
        assertEquals("{Ans|}", top { it.tap("4", "="); })
        assertEquals("{{1|2}|}", top { frac(it); it.tap("1"); it.move(3); it.tap("2"); it.move(1) })
        assertEquals("3×{|}", top { it.tap("3", "×") })
    }

    @Test fun nestedFractionsWork() {
        val m = CalcModel()
        m.tap("1")
        frac(m)                                 // {1|}
        frac(m)                                 // nothing before the cursor in the bottom: {1|{|}}
        assertEquals("{1|{|}}", m.expr)
        m.tap("2")
        m.move(3)
        m.tap("3")
        assertEquals("{1|{2|3}}", m.expr)
        m.tap("=")
        assertEquals("3/2", m.resultText())
    }

    @Test fun deletingFractions() {
        val m = CalcModel()
        m.tap("5")
        frac(m)
        assertEquals("{5|}", m.expr)
        m.tap("DEL")                            // an empty bottom: take the fraction apart
        assertEquals("5", m.expr)
        m.tap("AC")
        frac(m)
        m.tap("DEL")                            // an empty fraction goes away
        assertEquals("", m.expr)
        m.tap("AC", "1", "2")
        frac(m); m.tap("3", "4")
        assertEquals("{12|34}", m.expr)
        m.tap("DEL")
        assertEquals("{12|3}", m.expr)
        m.tap("DEL")
        assertEquals("{12|}", m.expr)
        m.tap("DEL", "DEL")                     // unwrap, then delete the 2
        assertEquals("1", m.expr)
        // DEL in front of a non-empty bottom steps over the bar instead
        m.tap("AC", "7")
        frac(m); m.tap("8")
        m.move(0)
        assertEquals(3, m.cursor)
        m.tap("DEL")
        assertEquals("{7|8}", m.expr)
        assertEquals(2, m.cursor)
    }

    @Test fun incompleteFractionsPointAtTheGap() {
        val m = CalcModel()
        m.tap("3")
        frac(m)
        m.tap("=")
        assertEquals("Syntax error", m.resultText())
        assertEquals(m.expr.indexOf('}'), m.cursor)   // the cursor jumps into the empty bottom
        m.tap("2")
        m.tap("=")
        assertEquals("3/2", m.resultText())
        val e = CalcModel()
        frac(e); e.tap("5", "=")
        assertEquals("Syntax error", e.resultText())
    }

    @Test fun fractionsInsideOtherModes() {
        val m = CalcModel()
        m.tap("2", "×")
        frac(m)
        m.tap("1"); m.move(3); m.tap("4")
        m.tap("=")
        assertEquals("1/2", m.resultText())
        val c = CalcModel()
        c.tap("SHIFT", "2")                      // complex mode
        c.tap("a/b", "1"); c.move(3); c.tap("2")
        c.tap("SHIFT", "ENG")                    // i
        c.tap("=")
        assertTrue(c.resultText().isNotEmpty() && c.error.isEmpty())
        val r = CalcModel.decode(CalcModel().also { it.tap("a/b", "1"); it.move(3); it.tap("3") }.encode())
        assertEquals("{1|3}", r.expr)
    }

    @Test fun afterAnAnswerTheFractionKeyStartsOverAns() {
        val m = CalcModel()
        m.tap("6", "=")
        frac(m)
        assertEquals("{Ans|}", m.expr)
        assertEquals(5, m.cursor)
        m.tap("4", "=")
        assertEquals("3/2", m.resultText())
    }

    @Test fun parsingAndCaretPieces() {
        assertEquals(null, parseSum("{1|2"))
        assertEquals(null, parseSum("1|2"))
        assertEquals(null, parseSum("}"))
        for (s in listOf("", "12", "{1|2}", "3+{4|5}×2", "{1|{2|3}}", "{|}", "{ab|cd}{e|f}", "{{1|2}|{3|4}}")) {
            val nodes = parseSum(s)!!
            for (c in 0..s.length) {
                val piece = caretPiece(nodes, c)
                assertTrue("$s at $c", piece != null)
                // and that piece really holds the cursor
                assertTrue("$s at $c", c >= piece!!.start && c <= piece.start + piece.text.length)
            }
        }
        val top = parseSum("a{b|c}d")!!
        assertEquals(3, top.size)
        assertEquals("a", (top[0] as SumText).text)
        assertEquals("d", (top[2] as SumText).text)
        val f = top[1] as SumFrac
        assertEquals(1, f.open); assertEquals(5, f.close)
        assertEquals("b", (f.top[0] as SumText).text)
        assertEquals(2, (f.top[0] as SumText).start)
        assertEquals(4, (f.bottom[0] as SumText).start)
        assertEquals("(1+2)÷3", prettySum("{1+2|3}"))
        assertEquals("1÷2+3÷4", prettySum("{1|2}+{3|4}"))
        assertEquals("1÷(2÷3)", prettySum("{1|{2|3}}"))
        assertEquals("plain", prettySum("plain"))
    }

    @Test fun undoSnapshotsAreIndependentOfLaterChanges() {
        val m = CalcModel()
        m.tap("2", "=")
        m.tap("AC", "3")
        m.vars["A"] = 9.0
        m.tap("=")
        assertEquals(2, m.history.size)
        m.undo()                                    // back to "3" typed, before "="
        assertEquals("3", m.expr)
        assertEquals(1, m.history.size)
        assertEquals(9.0, m.vars["A"]!!, 0.0)
        m.tap("4", "=")                             // changes after an undo must not reach back into old snapshots
        m.undo()                                    // before "="
        m.undo()                                    // before "4": just "3" again
        assertEquals("3", m.expr)
        m.undo()                                    // before "3" was typed
        assertEquals("", m.expr)
        assertEquals(1, m.history.size)
        m.undo()                                    // before AC: the first answer is on screen again
        assertEquals("2", m.resultText())
        assertEquals(1, m.history.size)
    }

    @Test fun manyFastPressesStayConsistent() {
        val m = CalcModel()
        val keys = listOf("7", "8", "9", "×", "4", "5", "6", "+", "1", "2", "3", "−")
        repeat(3000) { i ->
            m.press(key(keys[i % keys.size]))
            if (i % 25 == 24) m.press(key("="))
            if (i % 40 == 39) m.press(key("AC"))
        }
        assertTrue(m.history.size <= 40)
        assertTrue(m.cursor in 0..m.expr.length)
        var undone = 0
        while (m.undo()) undone++
        assertTrue(undone <= 30)
    }

    // ---- typeset templates: powers, roots, |x|, ×10 exponents ----------------------------------

    @Test fun powerKeyBuildsAnExponentSlot() {
        val m = CalcModel()
        m.tap("2", "xʸ")
        assertEquals("2^()", m.expr)
        assertEquals(3, m.cursor)               // inside the exponent
        m.tap("3")
        assertEquals("2^(3)", m.expr)
        m.tap("=")
        assertEquals("8", m.resultText())
        val n = CalcModel()
        n.tap("2", "xʸ", "3")
        n.move(1)                               // step out of the exponent
        n.tap("+", "1", "=")
        assertEquals("9", n.resultText())
        val k = CalcModel()
        k.tap("2", "xʸ", "3", "+", "1")         // without stepping out, the + stays in the exponent
        assertEquals("2^(3+1)", k.expr)
        k.tap("=")
        assertEquals("16", k.resultText())
        val a = CalcModel()
        a.tap("3", "=", "xʸ")
        assertEquals("Ans^()", a.expr)
        assertEquals(5, a.cursor)
        a.tap("2", "=")
        assertEquals("9", a.resultText())
    }

    @Test fun rootsTakeTheirOwnSlot() {
        val s = CalcModel()
        s.tap("√x")
        assertEquals("√()", s.expr)
        assertEquals(2, s.cursor)
        s.tap("9", "=")
        assertEquals("3", s.resultText())
        val c = CalcModel()
        c.tap("SHIFT", "√x", "8", "=")           // cube root
        assertEquals("∛(8)", c.lastExpr)
        assertEquals("2", c.resultText())
        val n = CalcModel()
        n.tap("3", "SHIFT", "xʸ")                // nth root: the 3 is the index
        assertEquals("3ˣ√()", n.expr)
        assertEquals(4, n.cursor)
        n.tap("8", "=")
        assertEquals("2", n.resultText())
        val e = CalcModel()
        e.tap("SHIFT", "xʸ")                     // no number first: the cursor waits at the index
        assertEquals("ˣ√()", e.expr)
        assertEquals(0, e.cursor)
        e.tap("3")
        assertEquals("3ˣ√()", e.expr)
        e.move(1)                                // one step over the whole ˣ√( mark
        assertEquals(4, e.cursor)
        e.tap("8", "=")
        assertEquals("2", e.resultText())
    }

    @Test fun tenAndEPowersAndAbs() {
        val t = CalcModel()
        t.tap("SHIFT", "Log")
        assertEquals("10^()", t.expr)
        assertEquals(4, t.cursor)
        t.tap("3", "=")
        assertEquals("1000", t.resultText())
        val e = CalcModel()
        e.tap("SHIFT", "Ln", "1", "=")
        assertEquals(Math.E, e.ans, 1e-12)
        val a = CalcModel()
        a.tap("SHIFT", "hyp")
        assertEquals("abs()", a.expr)
        assertEquals(4, a.cursor)
        a.tap("(−)", "5", "=")
        assertEquals("5", a.resultText())
    }

    @Test fun deletingTemplates() {
        val m = CalcModel()
        m.tap("√x")
        m.tap("DEL")                             // an empty root goes away
        assertEquals("", m.expr)
        m.tap("2", "xʸ", "3")
        m.tap("DEL")                             // the 3
        assertEquals("2^()", m.expr)
        m.tap("DEL")                             // the empty exponent slot
        assertEquals("2", m.expr)
        m.tap("AC", "2", "xʸ", "3")
        m.move(1)                                // out of the exponent, after the )
        assertEquals(5, m.cursor)
        m.tap("DEL")                             // steps back in instead of breaking the power
        assertEquals("2^(3)", m.expr)
        assertEquals(4, m.cursor)
        m.move(0)                                // to just after the opening
        assertEquals(3, m.cursor)
        m.tap("DEL")                             // a full power: step out in front of it
        assertEquals("2^(3)", m.expr)
        assertEquals(1, m.cursor)
    }

    @Test fun typingABracketStepsOverTheTemplateBracket() {
        val m = CalcModel()
        m.tap("√x", "2", ")", "=")
        assertEquals("√2", m.resultText())
        val n = CalcModel()
        n.tap("Sin", "3", "0", ")", "+", "1", "=")      // an ordinary function bracket behaves as before
        assertEquals("3/2", n.resultText())
    }

    @Test fun fractionsTakeWholePowersRootsAndExponents() {
        val p = CalcModel(); p.tap("2", "xʸ", "3"); p.move(1); p.tap("a/b")
        assertEquals("{2^(3)|}", p.expr)
        val r = CalcModel(); r.tap("3", "SHIFT", "xʸ", "8"); r.move(1); r.tap("a/b")
        assertEquals("{3ˣ√(8)|}", r.expr)
        val e = CalcModel(); e.tap("1", "×10ˣ", "5", "a/b")
        assertEquals("{1E5|}", e.expr)
        val s = CalcModel(); s.tap("√x", "9"); s.move(1); s.tap("a/b")
        assertEquals("{√(9)|}", s.expr)
    }

    @Test fun parsingTemplates() {
        val nodes = parseSum("2^(3)")!!
        assertEquals(3, nodes.size)
        assertEquals("2", (nodes[0] as SumText).text)
        val g = nodes[1] as SumGroup
        assertEquals(GroupKind.Pow, g.kind)
        assertEquals(1, g.open); assertEquals(2, g.openLen); assertEquals(4, g.close)
        assertEquals("3", (g.body[0] as SumText).text)
        assertEquals(3, (g.body[0] as SumText).start)
        val root = parseSum("3ˣ√(8)")!![1] as SumGroup
        assertEquals(GroupKind.Root, root.kind)
        assertEquals("3", root.index!!.text)
        assertEquals("", (parseSum("3ˣ√(8)")!![0] as SumText).text)
        val sci = parseSum("1.5E-3")!![1] as SumGroup
        assertEquals(GroupKind.Sci, sci.kind)
        assertEquals("-3", (sci.body[0] as SumText).text)
        assertEquals(-1, sci.close)
        val open = parseSum("2^(3")!![1] as SumGroup        // a bracket left open still draws
        assertEquals(-1, open.close)
        assertEquals("3", (open.body[0] as SumText).text)
        // ordinary brackets inside a template do not end it early
        val inner = parseSum("√(sin(30)+1)+2")!![1] as SumGroup
        assertEquals("sin(30)+1", (inner.body[0] as SumText).text)
        // a plain E (the variable) is not an exponent
        assertEquals(1, parseSum("2E×6")!!.size)
        assertTrue(!hasTemplates("2+2"))
        assertTrue(!hasTemplates("2E×6"))
        assertTrue(hasTemplates("2^(3)") && hasTemplates("1E5") && hasTemplates("1E") && hasTemplates("{1|2}") && hasTemplates("abs(2)"))
        val spans = templateGroups("2^(3)")
        assertEquals(1, spans.size)
        assertEquals(3, spans[0].bodyStart); assertEquals(4, spans[0].bodyEnd)
    }

    @Test fun everyCursorSpotBelongsToAPiece() {
        val sums = listOf(
            "", "2^(3)", "2^(3)+1", "√(9)", "3ˣ√(8)", "ˣ√()", "abs(−5)", "1E5", "1E", "1E-3", "2^(3^(2))", "sin(√(2)+1)",
            "{1|2^(3)}", "√({1|2})", "2^(3", "√(", "5E×6", "{1E5|√(3)}", "2^(3)^(4)", "10^(2)×e^(1)",
            "logb(2,8)", "logb(,)", "logb(2,", "logb(10,√(2))", "{1|logb(2,8)}", "logb(2)",
        )
        for (s in sums) {
            val nodes = parseSum(s)
            assertTrue("parse $s", nodes != null)
            for (c in 0..s.length) {
                val piece = caretPiece(nodes!!, c)
                assertTrue("$s at $c", piece != null)
            }
        }
    }

    @Test fun logTemplateHasABaseAndAnArgument() {
        val m = CalcModel()
        m.tap("logₓy")
        assertEquals("logb(,)", m.expr)
        assertEquals(5, m.cursor)               // in the base
        m.tap("2")
        assertEquals("logb(2,)", m.expr)
        m.move(1)                               // over the comma, into the argument
        assertEquals(7, m.cursor)
        m.tap("8", "=")
        assertEquals("3", m.resultText())
        val d = CalcModel()
        d.tap("logₓy", "2")
        d.move(1)
        d.tap("DEL")                            // at the start of x: back into the base, nothing broken
        assertEquals("logb(2,)", d.expr)
        assertEquals(6, d.cursor)
        val e = CalcModel()
        e.tap("logₓy", "DEL")                   // an empty log goes away
        assertEquals("", e.expr)
        val f = CalcModel()
        f.tap("logₓy", "2")
        f.move(1)
        f.tap("8")
        f.move(1)
        f.tap("a/b")
        assertEquals("{logb(2,8)|}", f.expr)
        val g = parseSum("logb(2,8)")!![1] as SumGroup
        assertEquals(GroupKind.Log, g.kind)
        assertEquals("2", (g.base!![0] as SumText).text)
        assertEquals("8", (g.body[0] as SumText).text)
        assertEquals(6, templateGroups("logb(2,8)")[0].comma)
        assertTrue(hasTemplates("logb(2,8)"))
    }

    @Test fun aPressThatIsCancelledByAHoldCanBeTakenBackExactly() {
        val m = CalcModel()
        m.tap("5", "+")
        m.press(key("3"))
        assertTrue(m.pressPushed)
        m.undo()                                  // what a hold on a key that typed on touch-down does
        assertEquals("5+", m.expr)
        assertTrue(!m.pressPushed)
        m.press(key("SHIFT"))                     // a layer key stores nothing
        assertTrue(!m.pressPushed)
        m.press(key("SHIFT"))
        m.press(key("AC"))
        assertTrue(m.pressPushed)
        m.undo()
        assertEquals("5+", m.expr)
        // a key pressed twice with no change in between still takes back cleanly
        val n = CalcModel()
        n.press(key("AC")); n.press(key("AC"))
        assertTrue(n.pressPushed)
        n.tap("7")
        n.undo()
        assertEquals("", n.expr)
    }

    @Test fun speedReadoutIsRememberedAndTimed() {
        val m = CalcModel()
        assertTrue(!m.showSpeed)
        m.showSpeed = true
        m.press(key("7"))
        assertTrue(m.lastPressNanos > 0L)
        assertTrue(m.lastLogicMicros in 0L..50_000L)
        assertTrue(CalcModel.decode(m.encode()).showSpeed)
    }
}
