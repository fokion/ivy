package xyz.fokion.ivy.core.expr;

import java.util.List;

/** The syntax tree of an expression; {@code start} and {@code end} delimit its source text. */
sealed interface Node {

    int start();

    int end();

    record Literal(Object value, int start, int end) implements Node {
    }

    record Ident(String name, int start, int end) implements Node {
    }

    /** {@code object.name} or {@code object?.name}. */
    record Member(Node object, String name, int start, int end) implements Node {
    }

    /** {@code object[index]}. */
    record Index(Node object, Node index, int start, int end) implements Node {
    }

    /** {@code fn(args)} for a function, {@code object.name(args)} for a method. */
    record Call(Node callee, List<Node> args, int start, int end) implements Node {
    }

    record Unary(String op, Node operand, int start, int end) implements Node {
    }

    record Binary(String op, Node left, Node right, int start, int end) implements Node {
    }

    record Conditional(Node test, Node then, Node otherwise, int start, int end) implements Node {
    }

    record ArrayLiteral(List<Node> items, int start, int end) implements Node {
    }

    record ObjectLiteral(List<String> keys, List<Node> values, int start, int end) implements Node {
    }

    /** {@code x => body}. */
    record Arrow(String param, Node body, int start, int end) implements Node {
    }
}
