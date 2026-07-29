package io.github.aigoodle.tool.builtin;

import io.github.aigoodle.tool.AbstractAgentTool;

import java.util.Map;

/**
 * Evaluates an arithmetic expression ({@code + - * /}, parentheses, decimals, unary
 * minus) with a small recursive-descent parser — no scripting engine required.
 */
public class CalculatorTool extends AbstractAgentTool {

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "Evaluate a basic arithmetic expression. Argument: 'expression' (e.g. '2*(3+4)/7').";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"}},\"required\":[\"expression\"]}";
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String expr = str(args, "expression");
        if (expr == null || expr.isBlank()) {
            return "error: missing 'expression'";
        }
        try {
            double result = new Parser(expr).parse();
            // Render integers without a trailing .0
            if (result == Math.rint(result) && !Double.isInfinite(result)) {
                return String.valueOf((long) result);
            }
            return String.valueOf(result);
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /** Grammar: expr = term (('+'|'-') term)*; term = factor (('*'|'/') factor)*; factor = number | '(' expr ')' | '-' factor. */
    private static final class Parser {
        private final String s;
        private int pos = -1;
        private int ch;

        Parser(String s) {
            this.s = s;
        }

        double parse() {
            next();
            double v = expr();
            if (ch != -1) {
                throw new IllegalArgumentException("unexpected char: " + (char) ch);
            }
            return v;
        }

        void next() {
            ch = (++pos < s.length()) ? s.charAt(pos) : -1;
        }

        boolean eat(int c) {
            while (ch == ' ') {
                next();
            }
            if (ch == c) {
                next();
                return true;
            }
            return false;
        }

        double expr() {
            double x = term();
            for (; ; ) {
                if (eat('+')) {
                    x += term();
                } else if (eat('-')) {
                    x -= term();
                } else {
                    return x;
                }
            }
        }

        double term() {
            double x = factor();
            for (; ; ) {
                if (eat('*')) {
                    x *= factor();
                } else if (eat('/')) {
                    x /= factor();
                } else {
                    return x;
                }
            }
        }

        double factor() {
            if (eat('+')) {
                return factor();
            }
            if (eat('-')) {
                return -factor();
            }
            double x;
            int start = pos;
            if (eat('(')) {
                x = expr();
                if (!eat(')')) {
                    throw new IllegalArgumentException("missing ')'");
                }
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') {
                    next();
                }
                x = Double.parseDouble(s.substring(start, pos));
            } else {
                throw new IllegalArgumentException("unexpected: " + (char) ch);
            }
            return x;
        }
    }
}
