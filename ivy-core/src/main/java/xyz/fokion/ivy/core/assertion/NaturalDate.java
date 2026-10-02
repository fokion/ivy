package xyz.fokion.ivy.core.assertion;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Month;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Port of {@code tj/go-naturaldate} v1.3.0: parses expressions such as {@code now},
 * {@code tomorrow}, {@code 5 minutes ago}, {@code in 2 hours}, {@code last friday}.
 * <p>
 * The grammar is a PEG; actions are collected while parsing and run in order once the whole
 * input matched, as the generated Go parser does.
 */
final class NaturalDate {

    private NaturalDate() {
    }

    static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }

    private static final Duration DAY = Duration.ofHours(24);
    private static final Duration WEEK = Duration.ofDays(7);

    /** Mutable evaluation state, the Go {@code parser} struct. */
    private static final class Ctx {
        ZonedDateTime t;
        int number;
        Month month;
        DayOfWeek weekday;
        int direction = -1;
    }

    private static final class State {
        final String in;
        int pos;
        final List<Consumer<Ctx>> actions = new ArrayList<>();

        State(String in) {
            this.in = in;
        }
    }

    @FunctionalInterface
    private interface Rule {
        boolean parse(State s);
    }

    static ZonedDateTime parse(String input, ZonedDateTime ref) throws ParseException {
        State s = new State(input.toLowerCase());
        WS.parse(s);
        int count = 0;
        while (s.pos < s.in.length()) {
            if (!EXPR.parse(s)) {
                throw new ParseException("parse error near offset " + s.pos);
            }
            count++;
        }
        if (count == 0) {
            throw new ParseException("parse error: empty expression");
        }
        Ctx ctx = new Ctx();
        ctx.t = ref;
        s.actions.forEach(a -> a.accept(ctx));
        return ctx.t;
    }

    // ------------------------------------------------------------ combinators

    private static Rule seq(Rule... rules) {
        return s -> {
            int pos = s.pos;
            int actions = s.actions.size();
            for (Rule r : rules) {
                if (!r.parse(s)) {
                    s.pos = pos;
                    while (s.actions.size() > actions) {
                        s.actions.removeLast();
                    }
                    return false;
                }
            }
            return true;
        };
    }

    private static Rule choice(Rule... rules) {
        return s -> {
            for (Rule r : rules) {
                if (r.parse(s)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static Rule opt(Rule r) {
        return s -> {
            r.parse(s);
            return true;
        };
    }

    private static Rule lit(String text) {
        return s -> {
            if (s.in.startsWith(text, s.pos)) {
                s.pos += text.length();
                return true;
            }
            return false;
        };
    }

    private static Rule act(Consumer<Ctx> action) {
        return s -> {
            s.actions.add(action);
            return true;
        };
    }

    private static final Rule WS = s -> {
        while (s.pos < s.in.length()) {
            char c = s.in.charAt(s.pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                s.pos++;
            } else {
                break;
            }
        }
        return true;
    };

    /** A keyword followed by optional whitespace. */
    private static Rule kw(String... alternatives) {
        Rule[] rules = new Rule[alternatives.length];
        for (int i = 0; i < alternatives.length; i++) {
            rules[i] = lit(alternatives[i]);
        }
        return seq(choice(rules), WS);
    }

    private static Rule plural(String word) {
        return seq(lit(word), opt(lit("s")), WS);
    }

    // ------------------------------------------------------------ grammar

    private static final Rule DIGITS = s -> {
        int start = s.pos;
        while (s.pos < s.in.length() && Character.isDigit(s.in.charAt(s.pos))) {
            s.pos++;
        }
        if (s.pos == start) {
            return false;
        }
        String text = s.in.substring(start, s.pos);
        int n;
        try {
            n = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            n = 0;
        }
        int value = n;
        s.actions.add(c -> c.number = value);
        return true;
    };

    private static Rule word(String w, int n) {
        return seq(lit(w), WS, act(c -> c.number = n));
    }

    private static final Rule NUMBER = choice(
            seq(DIGITS, WS),
            word("one", 1), word("two", 2), word("three", 3), word("four", 4), word("five", 5),
            word("six", 6), word("seven", 7), word("eight", 8), word("nine", 9), word("ten", 10));

    private static final Rule WEEKDAY;
    private static final Rule MONTH;

    static {
        List<Rule> days = new ArrayList<>();
        for (DayOfWeek d : List.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)) {
            days.add(seq(lit(d.name().toLowerCase()), WS, act(c -> c.weekday = d)));
        }
        WEEKDAY = choice(days.toArray(Rule[]::new));
        List<Rule> months = new ArrayList<>();
        for (Month m : Month.values()) {
            months.add(seq(lit(m.name().toLowerCase()), WS, act(c -> c.month = m)));
        }
        MONTH = choice(months.toArray(Rule[]::new));
    }

    private static final Rule YEARS = plural("year");
    private static final Rule MONTHS = plural("month");
    private static final Rule WEEKS = plural("week");
    private static final Rule DAYS = plural("day");
    private static final Rule HOURS = plural("hour");
    private static final Rule MINUTES = plural("minute");
    private static final Rule AGO = kw("ago");
    private static final Rule FROM_NOW = kw("from now");
    private static final Rule NEXT = kw("next");
    private static final Rule LAST = kw("last", "past", "previous");
    private static final Rule IN_KW = kw("in an", "in a", "in");

    private static final Rule IN = seq(IN_KW, act(c -> c.number = 1));
    private static final Rule LAST_N = seq(LAST, act(c -> c.number = 1));
    private static final Rule NEXT_N = seq(NEXT, act(c -> c.number = 1));

    private interface Shift {
        ZonedDateTime apply(ZonedDateTime t, int n);
    }

    /** The five alternatives shared by the relative rules. */
    private static Rule relative(Rule unit, Shift ago, Shift fromNow, Shift last, Shift next, Shift directed) {
        List<Rule> alternatives = new ArrayList<>(List.of(
                seq(NUMBER, unit, AGO, act(c -> c.t = ago.apply(c.t, c.number))),
                seq(choice(seq(NUMBER, unit, FROM_NOW), seq(IN, opt(NUMBER), unit, opt(FROM_NOW))),
                        act(c -> c.t = fromNow.apply(c.t, c.number))),
                seq(LAST_N, opt(NUMBER), unit, act(c -> c.t = last.apply(c.t, c.number))),
                seq(NEXT_N, opt(NUMBER), unit, act(c -> c.t = next.apply(c.t, c.number)))));
        if (directed != null) {
            alternatives.add(seq(NUMBER, unit, act(c -> c.t = directed.apply(c.t, c.number * c.direction))));
        }
        return choice(alternatives.toArray(Rule[]::new));
    }

    private static ZonedDateTime truncateDay(ZonedDateTime t) {
        return t.truncatedTo(ChronoUnit.DAYS);
    }

    private static ZonedDateTime plus(ZonedDateTime t, Duration d, long n) {
        return t.plus(d.multipliedBy(n));
    }

    private static final Rule RELATIVE_MINUTES = relative(MINUTES,
            (t, n) -> plus(t, Duration.ofMinutes(1), -n), (t, n) -> plus(t, Duration.ofMinutes(1), n),
            (t, n) -> plus(t, Duration.ofMinutes(1), -n), (t, n) -> plus(t, Duration.ofMinutes(1), n),
            (t, n) -> plus(t, Duration.ofMinutes(1), n));

    private static final Rule RELATIVE_HOURS = relative(HOURS,
            (t, n) -> plus(t, Duration.ofHours(1), -n), (t, n) -> plus(t, Duration.ofHours(1), n),
            (t, n) -> plus(t, Duration.ofHours(1), -n), (t, n) -> plus(t, Duration.ofHours(1), n),
            (t, n) -> plus(t, Duration.ofHours(1), n));

    private static final Rule RELATIVE_DAYS = relative(DAYS,
            (t, n) -> truncateDay(plus(t, DAY, -n)), (t, n) -> plus(t, DAY, n),
            (t, n) -> truncateDay(plus(t, DAY, -n)), (t, n) -> truncateDay(plus(t, DAY, n)),
            (t, n) -> truncateDay(plus(t, DAY, n)));

    private static final Rule RELATIVE_WEEKS = relative(WEEKS,
            (t, n) -> truncateDay(plus(t, WEEK, -n)), (t, n) -> plus(t, WEEK, n),
            (t, n) -> truncateDay(plus(t, WEEK, -n)), (t, n) -> truncateDay(plus(t, WEEK, n)),
            (t, n) -> truncateDay(plus(t, WEEK, n)));

    private static ZonedDateTime nextMonth(ZonedDateTime t, Month month) {
        int y = t.getYear();
        if (month.getValue() - t.getMonthValue() <= 0) {
            y++;
        }
        return date(y, month.getValue(), t.getDayOfMonth(), t.getHour(), t.getMinute(), t.getSecond(), t);
    }

    private static ZonedDateTime prevMonth(ZonedDateTime t, Month month) {
        int y = t.getYear();
        if (t.getMonthValue() - month.getValue() <= 0) {
            y--;
        }
        return date(y, month.getValue(), t.getDayOfMonth(), t.getHour(), t.getMinute(), t.getSecond(), t);
    }

    /** Go's {@code time.Date}, which normalizes out-of-range values. */
    private static ZonedDateTime date(int year, int month, int day, int hour, int min, int sec, ZonedDateTime zone) {
        return ZonedDateTime.of(year, 1, 1, 0, 0, 0, 0, zone.getZone())
                .plusMonths(month - 1L).plusDays(day - 1L).plusHours(hour).plusMinutes(min).plusSeconds(sec);
    }

    private static final Rule RELATIVE_MONTH = choice(
            relative(MONTHS,
                    (t, n) -> t.plusMonths(-n), (t, n) -> t.plusMonths(n),
                    (t, n) -> t.plusMonths(-n), (t, n) -> t.plusMonths(n),
                    null),
            seq(LAST, MONTH, act(c -> c.t = prevMonth(c.t, c.month))),
            seq(NEXT, MONTH, act(c -> c.t = nextMonth(c.t, c.month))),
            seq(MONTH, act(c -> c.t = c.direction < 0 ? prevMonth(c.t, c.month) : nextMonth(c.t, c.month))));

    private static final Rule RELATIVE_YEAR = choice(
            seq(NUMBER, YEARS, AGO, act(c -> c.t = c.t.plusYears(-c.number))),
            seq(choice(seq(NUMBER, YEARS, FROM_NOW), seq(IN, opt(NUMBER), YEARS, opt(FROM_NOW))),
                    act(c -> c.t = c.t.plusYears(c.number))),
            seq(LAST_N, opt(NUMBER), YEARS, act(c -> c.t = c.t.plusYears(-c.number))),
            seq(NEXT_N, opt(NUMBER), YEARS, act(c -> c.t = c.t.plusYears(c.number))),
            seq(LAST, YEARS, act(c -> c.t = date(c.t.getYear() - 1, 1, 1, 0, 0, 0, c.t))),
            seq(NEXT, YEARS, act(c -> c.t = date(c.t.getYear() + 1, 1, 1, 0, 0, 0, c.t))));

    private static ZonedDateTime prevWeekday(ZonedDateTime t, DayOfWeek day) {
        int d = goWeekday(t.getDayOfWeek()) - goWeekday(day);
        if (d <= 0) {
            d += 7;
        }
        return plus(t, DAY, -d);
    }

    private static ZonedDateTime nextWeekday(ZonedDateTime t, DayOfWeek day) {
        int d = goWeekday(day) - goWeekday(t.getDayOfWeek());
        if (d <= 0) {
            d += 7;
        }
        return plus(t, DAY, d);
    }

    /** Go numbers weekdays from Sunday = 0. */
    private static int goWeekday(DayOfWeek d) {
        return d.getValue() % 7;
    }

    private static final Rule RELATIVE_WEEKDAYS = choice(
            seq(kw("today"), act(c -> c.t = truncateDay(c.t))),
            seq(kw("yesterday"), act(c -> c.t = truncateDay(plus(c.t, DAY, -1)))),
            seq(kw("tomorrow"), act(c -> c.t = truncateDay(plus(c.t, DAY, 1)))),
            seq(LAST, WEEKDAY, act(c -> c.t = truncateDay(prevWeekday(c.t, c.weekday)))),
            seq(NEXT, WEEKDAY, act(c -> c.t = truncateDay(nextWeekday(c.t, c.weekday)))),
            seq(WEEKDAY, act(c -> c.t = truncateDay(c.direction < 0
                    ? prevWeekday(c.t, c.weekday) : nextWeekday(c.t, c.weekday)))));

    private static final Rule ORDINAL = seq(choice(lit("st"), lit("nd"), lit("rd"), lit("th")), WS);

    private static final Rule DATE = seq(
            choice(seq(NUMBER, ORDINAL), seq(LAST_N, opt(NUMBER), NUMBER)),
            act(c -> c.t = date(c.t.getYear(), c.t.getMonthValue(), c.number,
                    c.t.getHour(), c.t.getMinute(), c.t.getSecond(), c.t)));

    private static final Rule MINUTES_CLOCK = seq(lit(":"), NUMBER,
            act(c -> c.t = date(c.t.getYear(), c.t.getMonthValue(), c.t.getDayOfMonth(), c.t.getHour(), c.number, 0, c.t)));
    private static final Rule SECONDS_CLOCK = seq(lit(":"), NUMBER,
            act(c -> c.t = date(c.t.getYear(), c.t.getMonthValue(), c.t.getDayOfMonth(), c.t.getHour(), c.t.getMinute(), c.number, c.t)));

    private static Rule clockAt(int offset) {
        return act(c -> c.t = date(c.t.getYear(), c.t.getMonthValue(), c.t.getDayOfMonth(), c.number + offset, 0, 0, c.t));
    }

    private static final Rule TIME = choice(
            seq(NUMBER, clockAt(0), opt(seq(MINUTES_CLOCK, opt(SECONDS_CLOCK))), kw("am")),
            seq(NUMBER, clockAt(12), opt(seq(MINUTES_CLOCK, opt(SECONDS_CLOCK))), kw("pm")),
            seq(NUMBER, clockAt(0), opt(seq(MINUTES_CLOCK, opt(SECONDS_CLOCK)))));

    private static final Rule WORD = s -> {
        int start = s.pos;
        while (s.pos < s.in.length() && s.in.charAt(s.pos) >= 'a' && s.in.charAt(s.pos) <= 'z') {
            s.pos++;
        }
        if (s.pos == start) {
            return false;
        }
        return WS.parse(s);
    };

    private static final Rule EXPR = choice(
            kw("now"), RELATIVE_MINUTES, RELATIVE_HOURS, RELATIVE_DAYS, RELATIVE_WEEKS, RELATIVE_WEEKDAYS,
            RELATIVE_MONTH, RELATIVE_YEAR, DATE, TIME, WORD);
}
