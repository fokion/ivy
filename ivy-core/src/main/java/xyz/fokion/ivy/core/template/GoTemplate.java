package xyz.fokion.ivy.core.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.core.util.GoStrings;
import xyz.fokion.ivy.spi.util.GoFormat;

/**
 * The subset of Go's {@code text/template} that venom suites rely on: text, actions with
 * pipelines, field chains, literals, function calls, parentheses, comments and trim markers.
 * Control structures ({@code if}, {@code range}...) and variables are rejected at parse time.
 * <p>
 * Data is a tree of {@link Val} nodes holding strings. A missing value is {@code null}, which
 * prints as {@code <no value>} like Go's invalid {@code reflect.Value}.
 */
public final class GoTemplate {

    /** A template function; arguments are unwrapped ({@link Val} becomes its leaf value). */
    @FunctionalInterface
    public interface Function {
        Object call(List<Object> args) throws Exception;
    }

    /**
     * A map node of the data tree that can also carry its own value, the way venom's {@code val}
     * type prints its {@code "_"} entry: it supports variables like {@code a.b} and {@code a}.
     */
    public static final class Val {
        private final Map<String, Object> children = new LinkedHashMap<>();
        private Object leaf;
        private boolean hasLeaf;

        public Map<String, Object> children() {
            return children;
        }

        public Object leaf() {
            return leaf;
        }

        public void setLeaf(Object leaf) {
            this.leaf = leaf;
            this.hasLeaf = true;
        }

        public boolean hasLeaf() {
            return hasLeaf;
        }

        @Override
        public String toString() {
            return GoFormat.sprint(leaf);
        }
    }

    public static final class TemplateException extends Exception {
        public TemplateException(String message) {
            super(message);
        }
    }

    private static final Object NO_FINAL = new Object();

    private final String name;
    private final String input;
    private final List<Object> nodes;
    private final Map<String, Function> funcs;

    private GoTemplate(String name, String input, List<Object> nodes, Map<String, Function> funcs) {
        this.name = name;
        this.input = input;
        this.nodes = nodes;
        this.funcs = funcs;
    }

    public static GoTemplate parse(String name, String text, Map<String, Function> funcs) throws TemplateException {
        Parser p = new Parser(name, text, funcs);
        return new GoTemplate(name, text, p.parse(), funcs);
    }

    public String execute(Object data) throws TemplateException {
        StringBuilder out = new StringBuilder();
        for (Object node : nodes) {
            if (node instanceof String text) {
                out.append(text);
            } else {
                Pipe pipe = (Pipe) node;
                Object value = evalPipeline(data, pipe);
                out.append(print(value));
            }
        }
        return out.toString();
    }

    static String print(Object value) {
        return switch (value) {
            case null -> "<no value>";
            case Val v -> GoFormat.sprint(v.leaf());
            default -> GoFormat.sprint(value);
        };
    }

    // ---------------------------------------------------------------- AST

    private sealed interface Arg permits FieldArg, DotArg, LiteralArg, IdentArg, PipeArg {
        int pos();
    }

    private record FieldArg(List<String> idents, int pos) implements Arg {
        String text() {
            return "." + String.join(".", idents);
        }
    }

    private record DotArg(int pos) implements Arg {
    }

    private record LiteralArg(Object value, String text, int pos) implements Arg {
    }

    private record IdentArg(String name, int pos) implements Arg {
    }

    private record PipeArg(Pipe pipe, int pos) implements Arg {
    }

    private record Command(List<Arg> args, int pos) {
    }

    private record Pipe(List<Command> commands, int pos) {
    }

    // ---------------------------------------------------------------- execution

    private Object evalPipeline(Object dot, Pipe pipe) throws TemplateException {
        Object value = NO_FINAL;
        for (Command cmd : pipe.commands()) {
            value = evalCommand(dot, cmd, value);
        }
        return value == NO_FINAL ? null : value;
    }

    private Object evalCommand(Object dot, Command cmd, Object fin) throws TemplateException {
        Arg first = cmd.args().getFirst();
        switch (first) {
            case FieldArg f -> {
                if (cmd.args().size() > 1 || fin != NO_FINAL) {
                    throw execError(f.pos(), f.text(), f.idents().getLast() + " is not a method but has arguments");
                }
                return evalField(dot, f);
            }
            case IdentArg id -> {
                return evalFunction(dot, id, cmd.args().subList(1, cmd.args().size()), fin);
            }
            case PipeArg p -> {
                notAFunction(cmd, fin, "(pipeline)");
                return evalPipeline(dot, p.pipe());
            }
            case DotArg d -> {
                notAFunction(cmd, fin, ".");
                return dot;
            }
            case LiteralArg l -> {
                notAFunction(cmd, fin, l.text());
                return l.value();
            }
        }
    }

    private void notAFunction(Command cmd, Object fin, String what) throws TemplateException {
        if (cmd.args().size() > 1 || fin != NO_FINAL) {
            throw execError(cmd.pos(), what, "can't give argument to non-function " + what);
        }
    }

    private Object evalField(Object dot, FieldArg f) throws TemplateException {
        Object receiver = dot;
        for (String ident : f.idents()) {
            switch (receiver) {
                case null -> {
                    return null;
                }
                case Val v -> receiver = v.children().get(ident);
                case Map<?, ?> m -> receiver = m.get(ident);
                default -> throw execError(f.pos(), f.text(),
                        "can't evaluate field " + ident + " in type " + goTypeName(receiver));
            }
        }
        return receiver;
    }

    private Object evalFunction(Object dot, IdentArg id, List<Arg> args, Object fin) throws TemplateException {
        Function fn = funcs.get(id.name());
        if (fn == null) {
            throw execError(id.pos(), id.name(), "\"" + id.name() + "\" is not a defined function");
        }
        List<Object> argv = new ArrayList<>();
        for (Arg a : args) {
            argv.add(unwrap(evalArg(dot, a)));
        }
        if (fin != NO_FINAL) {
            argv.add(unwrap(fin));
        }
        try {
            return fn.call(argv);
        } catch (TemplateException e) {
            throw e;
        } catch (Exception e) {
            throw execError(id.pos(), id.name(), "error calling " + id.name() + ": " + e.getMessage());
        }
    }

    private Object evalArg(Object dot, Arg a) throws TemplateException {
        return switch (a) {
            case FieldArg f -> evalField(dot, f);
            case DotArg d -> dot;
            case LiteralArg l -> l.value();
            case IdentArg id -> evalFunction(dot, id, List.of(), NO_FINAL);
            case PipeArg p -> evalPipeline(dot, p.pipe());
        };
    }

    private static Object unwrap(Object v) {
        return v instanceof Val val ? val.leaf() : v;
    }

    private static String goTypeName(Object v) {
        return switch (v) {
            case String s -> "string";
            case Long l -> "int64";
            case Integer i -> "int";
            case Double d -> "float64";
            case Boolean b -> "bool";
            default -> v.getClass().getSimpleName();
        };
    }

    private TemplateException execError(int pos, String context, String message) {
        return new TemplateException("template: " + name + ":" + location(input, pos)
                + ": executing \"" + name + "\" at <" + context + ">: " + message);
    }

    static String location(String input, int pos) {
        int line = 1;
        int lastNewline = -1;
        for (int i = 0; i < pos && i < input.length(); i++) {
            if (input.charAt(i) == '\n') {
                line++;
                lastNewline = i;
            }
        }
        return line + ":" + (pos - lastNewline - 1);
    }

    // ---------------------------------------------------------------- parsing

    private static final class Parser {
        private final String name;
        private final String in;
        private final Map<String, Function> funcs;
        private int pos;

        Parser(String name, String in, Map<String, Function> funcs) {
            this.name = name;
            this.in = in;
            this.funcs = funcs;
        }

        private TemplateException error(int at, String msg) {
            int line = 1;
            for (int i = 0; i < at && i < in.length(); i++) {
                if (in.charAt(i) == '\n') {
                    line++;
                }
            }
            return new TemplateException("template: " + name + ":" + line + ": " + msg);
        }

        List<Object> parse() throws TemplateException {
            List<Object> nodes = new ArrayList<>();
            boolean trimNextText = false;
            while (pos < in.length()) {
                int start = in.indexOf("{{", pos);
                String text = start < 0 ? in.substring(pos) : in.substring(pos, start);
                if (trimNextText) {
                    text = stripLeading(text);
                }
                if (start < 0) {
                    addText(nodes, text);
                    break;
                }
                pos = start + 2;
                if (pos + 1 < in.length() && in.charAt(pos) == '-' && isSpace(in.charAt(pos + 1))) {
                    text = stripTrailing(text);
                    pos += 2;
                }
                addText(nodes, text);
                trimNextText = action(nodes);
            }
            return nodes;
        }

        private static void addText(List<Object> nodes, String text) {
            if (!text.isEmpty()) {
                nodes.add(text);
            }
        }

        /** Parses one action after the left delimiter; returns whether it ends with a trim marker. */
        private boolean action(List<Object> nodes) throws TemplateException {
            int actionStart = pos;
            if (in.startsWith("/*", pos)) {
                int end = in.indexOf("*/", pos + 2);
                if (end < 0) {
                    throw error(actionStart, "unclosed comment");
                }
                pos = end + 2;
                Boolean trim = rightDelim();
                if (trim == null) {
                    throw error(actionStart, "comment ends before closing delimiter");
                }
                return trim;
            }
            Lexer lex = new Lexer(this);
            Pipe pipe = pipeline(lex, false);
            nodes.add(pipe);
            return lex.trimmed;
        }

        /** At the right delimiter: returns trim flag and consumes it, or null. */
        Boolean rightDelim() {
            if (in.startsWith("}}", pos)) {
                pos += 2;
                return false;
            }
            if (pos + 3 < in.length() + 1 && pos < in.length() && isSpace(in.charAt(pos))
                    && in.startsWith("-}}", pos + 1)) {
                pos += 4;
                return true;
            }
            return null;
        }

        private Pipe pipeline(Lexer lex, boolean inParens) throws TemplateException {
            int pipeStart = pos;
            List<Command> commands = new ArrayList<>();
            List<Arg> args = new ArrayList<>();
            int cmdStart = pos;
            while (true) {
                Token t = lex.next();
                switch (t.kind) {
                    case END -> {
                        if (inParens) {
                            throw error(t.pos, "unclosed left paren");
                        }
                        endCommand(commands, args, cmdStart, t.pos);
                        return new Pipe(commands, pipeStart);
                    }
                    case RPAREN -> {
                        if (!inParens) {
                            throw error(t.pos, "unexpected right paren");
                        }
                        endCommand(commands, args, cmdStart, t.pos);
                        return new Pipe(commands, pipeStart);
                    }
                    case PIPE -> {
                        endCommand(commands, args, cmdStart, t.pos);
                        args = new ArrayList<>();
                        cmdStart = pos;
                    }
                    case LPAREN -> args.add(new PipeArg(pipeline(lex, true), t.pos));
                    case FIELD -> {
                        if (!args.isEmpty() && args.getLast() instanceof FieldArg prev && t.adjacent) {
                            List<String> idents = new ArrayList<>(prev.idents());
                            idents.add(t.text);
                            args.set(args.size() - 1, new FieldArg(idents, prev.pos()));
                        } else {
                            args.add(new FieldArg(List.of(t.text), t.pos));
                        }
                    }
                    case DOT -> args.add(new DotArg(t.pos));
                    case LITERAL -> args.add(new LiteralArg(t.value, t.text, t.pos));
                    case IDENT -> {
                        if (!funcs.containsKey(t.text)) {
                            throw error(t.pos, "function \"" + t.text + "\" not defined");
                        }
                        args.add(new IdentArg(t.text, t.pos));
                    }
                }
            }
        }

        private void endCommand(List<Command> commands, List<Arg> args, int cmdStart, int at) throws TemplateException {
            if (args.isEmpty()) {
                throw error(at, "missing value for command");
            }
            commands.add(new Command(args, cmdStart));
        }

        static String stripLeading(String s) {
            int i = 0;
            while (i < s.length() && isSpace(s.charAt(i))) {
                i++;
            }
            return s.substring(i);
        }

        static String stripTrailing(String s) {
            int i = s.length();
            while (i > 0 && isSpace(s.charAt(i - 1))) {
                i--;
            }
            return s.substring(0, i);
        }
    }

    static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static boolean isAlphaNumeric(int c) {
        return c == '_' || Character.isLetter(c) || Character.isDigit(c);
    }

    private enum Kind {
        END, PIPE, LPAREN, RPAREN, FIELD, DOT, LITERAL, IDENT
    }

    private record Token(Kind kind, String text, Object value, int pos, boolean adjacent) {
        Token(Kind kind, String text, int pos, boolean adjacent) {
            this(kind, text, null, pos, adjacent);
        }
    }

    private static final java.util.Set<String> KEYWORDS = java.util.Set.of(
            "if", "else", "end", "range", "with", "define", "template", "block", "break", "continue");

    /** Tokenizes the inside of one action. */
    private static final class Lexer {
        private final Parser p;
        boolean trimmed;
        private boolean lastWasSpace = true;

        Lexer(Parser p) {
            this.p = p;
        }

        Token next() throws TemplateException {
            String in = p.in;
            boolean sawSpace = false;
            while (true) {
                Boolean trim = p.rightDelim();
                if (trim != null) {
                    trimmed = trim;
                    return new Token(Kind.END, "", p.pos, false);
                }
                if (p.pos >= in.length()) {
                    throw p.error(p.pos, "unclosed action");
                }
                char c = in.charAt(p.pos);
                if (isSpace(c)) {
                    p.pos++;
                    sawSpace = true;
                    continue;
                }
                break;
            }
            boolean adjacent = !sawSpace && !lastWasSpace;
            lastWasSpace = false;
            int start = p.pos;
            char c = in.charAt(start);
            switch (c) {
                case '|' -> {
                    p.pos++;
                    lastWasSpace = true;
                    return new Token(Kind.PIPE, "|", start, false);
                }
                case '(' -> {
                    p.pos++;
                    lastWasSpace = true;
                    return new Token(Kind.LPAREN, "(", start, false);
                }
                case ')' -> {
                    p.pos++;
                    return new Token(Kind.RPAREN, ")", start, false);
                }
                case '"' -> {
                    return new Token(Kind.LITERAL, in.substring(start, endOfQuoted('"')), unquoted(start), start, false);
                }
                case '`' -> {
                    int end = in.indexOf('`', start + 1);
                    if (end < 0) {
                        throw p.error(start, "unterminated raw quoted string");
                    }
                    p.pos = end + 1;
                    String raw = in.substring(start + 1, end);
                    return new Token(Kind.LITERAL, in.substring(start, p.pos), raw, start, false);
                }
                case '\'' -> {
                    int end = endOfQuoted('\'');
                    String body = GoStrings.unquote(in.substring(start, end));
                    return new Token(Kind.LITERAL, in.substring(start, end), (long) body.codePointAt(0), start, false);
                }
                case '$' -> throw p.error(start, "variables are not supported");
                case ':', '=' -> throw p.error(start, "assignments are not supported");
                default -> {
                }
            }
            if (c == '.') {
                if (start + 1 < in.length() && Character.isDigit(in.charAt(start + 1))) {
                    return number(start);
                }
                p.pos++;
                int identStart = p.pos;
                while (p.pos < in.length() && isAlphaNumeric(in.codePointAt(p.pos))) {
                    p.pos += Character.charCount(in.codePointAt(p.pos));
                }
                if (identStart == p.pos) {
                    return new Token(Kind.DOT, ".", start, adjacent);
                }
                checkTerminator();
                return new Token(Kind.FIELD, in.substring(identStart, p.pos), start, adjacent);
            }
            if (c == '+' || c == '-' || Character.isDigit(c)) {
                return number(start);
            }
            if (isAlphaNumeric(in.codePointAt(start))) {
                while (p.pos < in.length() && isAlphaNumeric(in.codePointAt(p.pos))) {
                    p.pos += Character.charCount(in.codePointAt(p.pos));
                }
                String word = in.substring(start, p.pos);
                checkTerminator();
                if (KEYWORDS.contains(word)) {
                    throw p.error(start, "\"" + word + "\" actions are not supported");
                }
                return switch (word) {
                    case "true" -> new Token(Kind.LITERAL, word, Boolean.TRUE, start, false);
                    case "false" -> new Token(Kind.LITERAL, word, Boolean.FALSE, start, false);
                    case "nil" -> new Token(Kind.LITERAL, word, null, start, false);
                    default -> new Token(Kind.IDENT, word, start, false);
                };
            }
            throw p.error(start, "unexpected \"" + c + "\" in command");
        }

        private void checkTerminator() throws TemplateException {
            String in = p.in;
            if (p.pos >= in.length()) {
                return;
            }
            char c = in.charAt(p.pos);
            if (isSpace(c) || c == '.' || c == ',' || c == '|' || c == ':' || c == ')' || c == '('
                    || in.startsWith("}}", p.pos)) {
                return;
            }
            throw p.error(p.pos, "unexpected bad character U+" + String.format("%04X", (int) c) + " '" + c + "' in command");
        }

        private int endOfQuoted(char quote) throws TemplateException {
            String in = p.in;
            int i = p.pos + 1;
            while (true) {
                if (i >= in.length() || in.charAt(i) == '\n') {
                    throw p.error(p.pos, "unterminated quoted string");
                }
                char c = in.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    p.pos = i + 1;
                    return i + 1;
                }
                i++;
            }
        }

        private String unquoted(int start) throws TemplateException {
            try {
                return GoStrings.unquote(p.in.substring(start, p.pos));
            } catch (IllegalArgumentException e) {
                throw p.error(start, e.getMessage());
            }
        }

        private Token number(int start) throws TemplateException {
            String in = p.in;
            int i = start;
            if (in.charAt(i) == '+' || in.charAt(i) == '-') {
                i++;
            }
            while (i < in.length()) {
                char c = in.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '.' || c == '_'
                        || ((c == '+' || c == '-') && (in.charAt(i - 1) == 'e' || in.charAt(i - 1) == 'E'))) {
                    i++;
                } else {
                    break;
                }
            }
            p.pos = i;
            String text = in.substring(start, i);
            Object value;
            try {
                if (text.matches("[+-]?(0[xXoObB])?[0-9a-fA-F_]+") && !text.matches("[+-]?\\d*[eE].*")) {
                    value = parseInt(text);
                } else {
                    value = Double.parseDouble(text.replace("_", ""));
                }
            } catch (NumberFormatException e) {
                throw p.error(start, "bad number syntax: \"" + text + "\"");
            }
            checkTerminator();
            return new Token(Kind.LITERAL, text, value, start, false);
        }

        private static Object parseInt(String text) {
            long v = xyz.fokion.ivy.core.util.Cast.parseGoInt(text);
            return v;
        }
    }
}
