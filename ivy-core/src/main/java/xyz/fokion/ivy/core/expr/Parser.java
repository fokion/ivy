package xyz.fokion.ivy.core.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses the expression language, a subset of JavaScript expressions:
 *
 * <pre>
 * conditional  := nullish ('?' expr ':' expr)?
 * nullish      := or ('??' or)*
 * or           := and ('||' and)*
 * and          := equality ('&amp;&amp;' equality)*
 * equality     := relational (('==' | '!=' | '===' | '!==') relational)*
 * relational   := additive (('&lt;' | '&lt;=' | '&gt;' | '&gt;=' | 'contains' | 'matches' | 'in') additive)*
 * additive     := multiplicative (('+' | '-') multiplicative)*
 * multiplicative := unary (('*' | '/' | '%') unary)*
 * unary        := ('!' | '-' | '+') unary | postfix
 * postfix      := primary ('.' name | '?.' name | '[' expr ']' | '(' args ')')*
 * primary      := number | string | true | false | null | name | '(' expr ')' | array | object | arrow
 * </pre>
 */
final class Parser {

    private static final Set<String> WORD_OPERATORS = Set.of("contains", "matches", "in");

    private enum Kind {
        NUMBER, STRING, NAME, PUNCT, EOF
    }

    private record Token(Kind kind, String text, Object value, int start, int end) {
        boolean is(String punct) {
            return kind == Kind.PUNCT && text.equals(punct);
        }

        boolean isWord(String word) {
            return kind == Kind.NAME && text.equals(word);
        }
    }

    private final String source;
    private final List<Token> tokens;
    private int pos;

    private Parser(String source) {
        this.source = source;
        this.tokens = lex(source);
    }

    static Node parse(String source) {
        Parser p = new Parser(source);
        if (p.peek().kind == Kind.EOF) {
            throw new ExprException("empty expression", source, 0);
        }
        Node n = p.expression();
        if (p.peek().kind != Kind.EOF) {
            Token t = p.peek();
            throw new ExprException("unexpected '" + t.text + "'", source, t.start);
        }
        return n;
    }

    // ------------------------------------------------------------ lexer

    private static final String[] PUNCTUATION = {
        "===", "!==", "...", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "=>",
        "(", ")", "[", "]", "{", "}", ",", ".", ":", "?", "!", "<", ">", "+", "-", "*", "/", "%",
    };

    private static List<Token> lex(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                boolean decimal = false;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                if (i < s.length() && s.charAt(i) == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1))) {
                    decimal = true;
                    i++;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                }
                if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                    int save = i;
                    i++;
                    if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                        i++;
                    }
                    if (i < s.length() && Character.isDigit(s.charAt(i))) {
                        decimal = true;
                        while (i < s.length() && Character.isDigit(s.charAt(i))) {
                            i++;
                        }
                    } else {
                        i = save;
                    }
                }
                String text = s.substring(start, i).replace("_", "");
                Object value;
                if (decimal) {
                    value = Double.parseDouble(text);
                } else {
                    try {
                        value = Long.parseLong(text);
                    } catch (NumberFormatException e) {
                        value = Double.parseDouble(text);
                    }
                }
                out.add(new Token(Kind.NUMBER, s.substring(start, i), value, start, i));
                continue;
            }
            if (c == '"' || c == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (d == c) {
                        closed = true;
                        i++;
                        break;
                    }
                    if (d == '\\' && i + 1 < s.length()) {
                        char e = s.charAt(i + 1);
                        i += 2;
                        switch (e) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case 'r' -> sb.append('\r');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case '0' -> sb.append('\0');
                            case 'u' -> {
                                if (i + 4 > s.length()) {
                                    throw new ExprException("invalid \\u escape", s, i - 2);
                                }
                                try {
                                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                } catch (NumberFormatException ex) {
                                    throw new ExprException("invalid \\u escape", s, i - 2);
                                }
                                i += 4;
                            }
                            // anything else stands for itself: \" \' \\ and regex escapes such as \d
                            default -> {
                                if (e == c || e == '\\' || e == '"' || e == '\'') {
                                    sb.append(e);
                                } else {
                                    sb.append('\\').append(e);
                                }
                            }
                        }
                        continue;
                    }
                    sb.append(d);
                    i++;
                }
                if (!closed) {
                    throw new ExprException("unterminated string", s, start);
                }
                out.add(new Token(Kind.STRING, s.substring(start, i), sb.toString(), start, i));
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$') {
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '$')) {
                    i++;
                }
                out.add(new Token(Kind.NAME, s.substring(start, i), null, start, i));
                continue;
            }
            String punct = null;
            for (String p : PUNCTUATION) {
                if (s.startsWith(p, i)) {
                    punct = p;
                    break;
                }
            }
            if (punct == null) {
                throw new ExprException("unexpected character '" + c + "'", s, i);
            }
            i += punct.length();
            out.add(new Token(Kind.PUNCT, punct, null, start, i));
        }
        out.add(new Token(Kind.EOF, "end of expression", null, s.length(), s.length()));
        return out;
    }

    // ------------------------------------------------------------ parser

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(pos + ahead, tokens.size() - 1));
    }

    private Token next() {
        Token t = tokens.get(pos);
        if (t.kind != Kind.EOF) {
            pos++;
        }
        return t;
    }

    private Token expect(String punct) {
        Token t = next();
        if (!t.is(punct)) {
            throw new ExprException("expected '" + punct + "' but found '" + t.text + "'", source, t.start);
        }
        return t;
    }

    private Node expression() {
        Token t = peek();
        // x => body
        if (t.kind == Kind.NAME && peek(1).is("=>")) {
            next();
            next();
            Node body = expression();
            return new Node.Arrow(t.text, body, t.start, body.end());
        }
        // (x) => body
        if (t.is("(") && peek(1).kind == Kind.NAME && peek(2).is(")") && peek(3).is("=>")) {
            next();
            String param = next().text;
            next();
            next();
            Node body = expression();
            return new Node.Arrow(param, body, t.start, body.end());
        }
        return conditional();
    }

    private Node conditional() {
        Node test = nullish();
        if (peek().is("?")) {
            next();
            Node then = expression();
            expect(":");
            Node otherwise = expression();
            return new Node.Conditional(test, then, otherwise, test.start(), otherwise.end());
        }
        return test;
    }

    private Node nullish() {
        Node left = or();
        while (peek().is("??")) {
            next();
            Node right = or();
            left = new Node.Binary("??", left, right, left.start(), right.end());
        }
        return left;
    }

    private Node or() {
        Node left = and();
        while (peek().is("||")) {
            next();
            Node right = and();
            left = new Node.Binary("||", left, right, left.start(), right.end());
        }
        return left;
    }

    private Node and() {
        Node left = equality();
        while (peek().is("&&")) {
            next();
            Node right = equality();
            left = new Node.Binary("&&", left, right, left.start(), right.end());
        }
        return left;
    }

    private Node equality() {
        Node left = relational();
        while (peek().is("==") || peek().is("!=") || peek().is("===") || peek().is("!==")) {
            String op = next().text;
            Node right = relational();
            left = new Node.Binary(op, left, right, left.start(), right.end());
        }
        return left;
    }

    private Node relational() {
        Node left = additive();
        while (true) {
            Token t = peek();
            String op;
            if (t.is("<") || t.is("<=") || t.is(">") || t.is(">=")) {
                op = t.text;
            } else if (t.kind == Kind.NAME && WORD_OPERATORS.contains(t.text)) {
                op = t.text;
            } else if (t.is("!") && peek(1).kind == Kind.NAME && WORD_OPERATORS.contains(peek(1).text)
                    && peek(1).start == t.end) {
                // !contains, !matches, !in
                next();
                op = "!" + peek().text;
            } else {
                return left;
            }
            next();
            Node right = additive();
            left = new Node.Binary(op, left, right, left.start(), right.end());
        }
    }

    private Node additive() {
        Node left = multiplicative();
        while (peek().is("+") || peek().is("-")) {
            String op = next().text;
            Node right = multiplicative();
            left = new Node.Binary(op, left, right, left.start(), right.end());
        }
        return left;
    }

    private Node multiplicative() {
        Node left = unary();
        while (peek().is("*") || peek().is("/") || peek().is("%")) {
            String op = next().text;
            Node right = unary();
            left = new Node.Binary(op, left, right, left.start(), right.end());
        }
        return left;
    }

    private Node unary() {
        Token t = peek();
        if (t.is("!") || t.is("-") || t.is("+")) {
            next();
            Node operand = unary();
            return new Node.Unary(t.text, operand, t.start, operand.end());
        }
        return postfix();
    }

    private Node postfix() {
        Node n = primary();
        while (true) {
            Token t = peek();
            if (t.is(".") || t.is("?.")) {
                next();
                Token name = next();
                if (name.kind != Kind.NAME) {
                    throw new ExprException("expected a property name after '" + t.text + "'", source, name.start);
                }
                n = new Node.Member(n, name.text, n.start(), name.end);
            } else if (t.is("[")) {
                next();
                Node index = expression();
                Token close = expect("]");
                n = new Node.Index(n, index, n.start(), close.end);
            } else if (t.is("(")) {
                if (!(n instanceof Node.Ident) && !(n instanceof Node.Member)) {
                    throw new ExprException("only functions and methods can be called", source, t.start);
                }
                next();
                List<Node> args = new ArrayList<>();
                if (!peek().is(")")) {
                    args.add(expression());
                    while (peek().is(",")) {
                        next();
                        args.add(expression());
                    }
                }
                Token close = expect(")");
                n = new Node.Call(n, args, n.start(), close.end);
            } else {
                return n;
            }
        }
    }

    private Node primary() {
        Token t = next();
        switch (t.kind) {
            case NUMBER, STRING -> {
                return new Node.Literal(t.value, t.start, t.end);
            }
            case NAME -> {
                return switch (t.text) {
                    case "true" -> new Node.Literal(Boolean.TRUE, t.start, t.end);
                    case "false" -> new Node.Literal(Boolean.FALSE, t.start, t.end);
                    case "null", "undefined" -> new Node.Literal(null, t.start, t.end);
                    default -> new Node.Ident(t.text, t.start, t.end);
                };
            }
            case EOF -> throw new ExprException("unexpected end of expression", source, t.start);
            default -> {
            }
        }
        if (t.is("(")) {
            Node inner = expression();
            expect(")");
            return inner;
        }
        if (t.is("[")) {
            List<Node> items = new ArrayList<>();
            if (!peek().is("]")) {
                items.add(expression());
                while (peek().is(",")) {
                    next();
                    if (peek().is("]")) {
                        break;
                    }
                    items.add(expression());
                }
            }
            Token close = expect("]");
            return new Node.ArrayLiteral(items, t.start, close.end);
        }
        if (t.is("{")) {
            List<String> keys = new ArrayList<>();
            List<Node> values = new ArrayList<>();
            while (!peek().is("}")) {
                Token key = next();
                if (key.kind != Kind.NAME && key.kind != Kind.STRING && key.kind != Kind.NUMBER) {
                    throw new ExprException("expected a key but found '" + key.text + "'", source, key.start);
                }
                String k = key.kind == Kind.NAME ? key.text : String.valueOf(key.value);
                keys.add(k);
                if (peek().is(":")) {
                    next();
                    values.add(expression());
                } else if (key.kind == Kind.NAME) {
                    // {name} is {name: name}
                    values.add(new Node.Ident(k, key.start, key.end));
                } else {
                    throw new ExprException("expected ':' after the key", source, peek().start);
                }
                if (!peek().is(",")) {
                    break;
                }
                next();
            }
            Token close = expect("}");
            return new Node.ObjectLiteral(keys, values, t.start, close.end);
        }
        throw new ExprException("unexpected '" + t.text + "'", source, t.start);
    }
}
