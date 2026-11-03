package com.openobd.core.brand

/**
 * Evaluates Torque-style PID equations, e.g. "((A*256)+B)/16", "Signed(A)-40", "{B:3}", "(A<8)*B".
 *
 * Variables A..Z then AA..AZ, BA.. map to the response bytes after the request echo.
 * Supported: + - * / % ^, comparisons (< > <= >= = == !=), & | >> <<, parentheses, unary minus,
 * {X:n} (bit n of X), and functions Signed(x), Int16(hi,lo), Min, Max, Abs, Sqrt, Log, Exp.
 */
class Equation(val source: String) {
    private sealed interface Node
    private class Num(val v: Double) : Node
    private class Var(val index: Int) : Node
    private class Bit(val index: Int, val bit: Int) : Node
    private class Unary(val op: Char, val a: Node) : Node
    private class Bin(val op: String, val a: Node, val b: Node) : Node
    private class Call(val name: String, val args: List<Node>) : Node

    private val root: Node
    /** Highest byte index referenced; the response must contain at least this many bytes. */
    val bytesNeeded: Int

    init {
        val p = Parser(source)
        root = p.parseExpr()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("Unexpected '${p.rest()}' in equation")
        bytesNeeded = p.maxVar + 1
    }

    fun evaluate(bytes: IntArray): Double? = try {
        eval(root, bytes).takeIf { !it.isNaN() && !it.isInfinite() }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private fun eval(n: Node, b: IntArray): Double = when (n) {
        is Num -> n.v
        is Var -> b[n.index].toDouble()
        is Bit -> ((b[n.index] shr n.bit) and 1).toDouble()
        is Unary -> if (n.op == '-') -eval(n.a, b) else if (eval(n.a, b) == 0.0) 1.0 else 0.0
        is Bin -> {
            val x = eval(n.a, b); val y = eval(n.b, b)
            when (n.op) {
                "+" -> x + y; "-" -> x - y; "*" -> x * y; "/" -> x / y; "%" -> x % y
                "^" -> Math.pow(x, y)
                "&" -> (x.toLong() and y.toLong()).toDouble(); "|" -> (x.toLong() or y.toLong()).toDouble()
                ">>" -> (x.toLong() shr y.toInt()).toDouble(); "<<" -> (x.toLong() shl y.toInt()).toDouble()
                "<" -> if (x < y) 1.0 else 0.0; ">" -> if (x > y) 1.0 else 0.0
                "<=" -> if (x <= y) 1.0 else 0.0; ">=" -> if (x >= y) 1.0 else 0.0
                "=", "==" -> if (x == y) 1.0 else 0.0; "!=" -> if (x != y) 1.0 else 0.0
                else -> throw IllegalStateException(n.op)
            }
        }
        is Call -> {
            val a = n.args.map { eval(it, b) }
            when (n.name) {
                "SIGNED" -> {
                    val v = a[0].toLong()
                    when {
                        v < 0x100 -> (if (v >= 0x80) v - 0x100 else v).toDouble()
                        v < 0x10000 -> (if (v >= 0x8000) v - 0x10000 else v).toDouble()
                        else -> (if (v >= 0x800000) v - 0x1000000 else v).toDouble()
                    }
                }
                "INT16" -> { val v = (a[0].toInt() shl 8) or a[1].toInt(); (if (v >= 0x8000) v - 0x10000 else v).toDouble() }
                "MIN" -> a.min(); "MAX" -> a.max(); "ABS" -> Math.abs(a[0])
                "SQRT" -> Math.sqrt(a[0]); "LOG" -> Math.log(a[0]); "EXP" -> Math.exp(a[0])
                else -> throw IllegalStateException(n.name)
            }
        }
    }

    private class Parser(private val s: String) {
        var pos = 0
        var maxVar = -1

        fun atEnd() = pos >= s.length
        fun rest() = s.substring(pos)
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        private fun peekOp(vararg ops: String): String? { skipWs(); return ops.firstOrNull { s.startsWith(it, pos) } }

        fun parseExpr(): Node = parseCompare()

        private fun parseCompare(): Node {
            var left = parseBitwise()
            while (true) {
                val op = peekOp("<=", ">=", "==", "!=", "<", ">", "=") ?: break
                pos += op.length
                left = Bin(op, left, parseBitwise())
            }
            return left
        }

        private fun parseBitwise(): Node {
            var left = parseShift()
            while (true) {
                val op = peekOp("&", "|") ?: break
                pos += 1
                left = Bin(op, left, parseShift())
            }
            return left
        }

        private fun parseShift(): Node {
            var left = parseAdd()
            while (true) {
                val op = peekOp("<<", ">>") ?: break
                pos += 2
                left = Bin(op, left, parseAdd())
            }
            return left
        }

        private fun parseAdd(): Node {
            var left = parseMul()
            while (true) {
                val op = peekOp("+", "-") ?: break
                pos += 1
                left = Bin(op, left, parseMul())
            }
            return left
        }

        private fun parseMul(): Node {
            var left = parsePow()
            while (true) {
                val op = peekOp("*", "/", "%") ?: break
                pos += 1
                left = Bin(op, left, parsePow())
            }
            return left
        }

        private fun parsePow(): Node {
            val base = parseUnary()
            if (peekOp("^") != null) { pos++; return Bin("^", base, parsePow()) }
            return base
        }

        private fun parseUnary(): Node {
            skipWs()
            if (peekOp("-") != null) { pos++; return Unary('-', parseUnary()) }
            if (peekOp("+") != null) { pos++; return parseUnary() }
            if (peekOp("!") != null) { pos++; return Unary('!', parseUnary()) }
            return parsePrimary()
        }

        private fun varIndex(name: String): Int {
            val idx = if (name.length == 1) name[0] - 'A' else (name[0] - 'A' + 1) * 26 + (name[1] - 'A')
            maxVar = maxOf(maxVar, idx)
            return idx
        }

        private fun parsePrimary(): Node {
            skipWs()
            if (atEnd()) throw IllegalArgumentException("Equation ends unexpectedly")
            val c = s[pos]
            when {
                c == '(' -> { pos++; val e = parseExpr(); expect(')'); return e }
                c == '{' -> {
                    pos++; skipWs()
                    val name = readWord().uppercase()
                    expect(':')
                    skipWs()
                    val bit = readNumber().toInt()
                    expect('}')
                    return Bit(varIndex(name), bit)
                }
                c.isDigit() || c == '.' -> return Num(readNumber())
                c.isLetter() -> {
                    val word = readWord()
                    skipWs()
                    if (!atEnd() && s[pos] == '(') {
                        pos++
                        val args = mutableListOf<Node>()
                        skipWs()
                        if (s[pos] != ')') {
                            args += parseExpr()
                            while (peekOp(",") != null) { pos++; args += parseExpr() }
                        }
                        expect(')')
                        val name = word.uppercase()
                        val arity = mapOf("SIGNED" to 1..1, "INT16" to 2..2, "MIN" to 1..20, "MAX" to 1..20, "ABS" to 1..1, "SQRT" to 1..1, "LOG" to 1..1, "EXP" to 1..1)
                        val range = arity[name] ?: throw IllegalArgumentException("Unknown function $word")
                        if (args.size !in range) throw IllegalArgumentException("Wrong number of arguments for $word")
                        return Call(name, args)
                    }
                    val up = word.uppercase()
                    if (up.length > 2 || !up.all { it in 'A'..'Z' }) throw IllegalArgumentException("Unknown variable $word")
                    return Var(varIndex(up))
                }
                else -> throw IllegalArgumentException("Unexpected '$c'")
            }
        }

        private fun readWord(): String {
            val start = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_')) pos++
            return s.substring(start, pos)
        }

        private fun readNumber(): Double {
            val start = pos
            if (s.startsWith("0x", pos, true)) {
                pos += 2
                while (pos < s.length && s[pos].isLetterOrDigit()) pos++
                return s.substring(start + 2, pos).toLong(16).toDouble()
            }
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            return s.substring(start, pos).toDouble()
        }

        private fun expect(c: Char) {
            skipWs()
            if (atEnd() || s[pos] != c) throw IllegalArgumentException("Expected '$c'")
            pos++
        }
    }
}
