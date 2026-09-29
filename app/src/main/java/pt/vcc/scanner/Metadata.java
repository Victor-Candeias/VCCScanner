package pt.vcc.scanner;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.*;

public final class Metadata {
    public static final String CATEGORY="category", COMPANY="company", NIF="nif", DATE="date", TOTAL="total";
    private static final String UNKNOWN_CATEGORY="Outros";
    private Metadata() {}
    public static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
    public static String category(String text) {
        String s = normalize(text);
        if (s.contains("fatura") || s.contains("factura") || s.contains("invoice")) return "Faturas";
        if (s.contains("recibo") || s.contains("receipt")) return "Recibos";
        if (s.contains("contrato") || s.contains("contract")) return "Contratos";
        if (s.contains("manual") || s.contains("instrucoes")) return "Manuais";
        if (s.contains("cartao de cidadao") || s.contains("passaporte")) return "Pessoais";
        return UNKNOWN_CATEGORY;
    }
    private static String match(String regex, String text) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE).matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }
    public static void extract(Document d) {
        String category = category(d.text);
        String nif = match("(?:NIF|NIPC|contribuinte)\\s*[:º.n°-]*\\s*(?:PT\\s*)?(\\d{9})\\b", d.text);
        String date = match("\\b(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{4})\\b", d.text);
        String total = match("^\\s*(?:total(?:\\s+a\\s+pagar)?|valor\\s+total)\\s*[:€]*\\s*([\\d .]+[,\\.]\\d{2})", d.text);
        String company = java.util.Arrays.stream(d.text.split("\\R")).map(String::trim).filter(s -> s.length() > 3 && !normalize(s).matches(".*(fatura|factura|recibo|invoice).*" )).findFirst().orElse("");
        // A rotation or filter can temporarily blind the OCR: never replace a known value with an unknown one.
        if (!edited(d, CATEGORY) && !UNKNOWN_CATEGORY.equals(category)) d.category = category;
        if (!edited(d, NIF) && !nif.isEmpty()) d.nif = nif;
        if (!edited(d, DATE) && !date.isEmpty()) d.date = date;
        if (!edited(d, TOTAL) && !total.isEmpty()) d.total = total;
        if (!edited(d, COMPANY) && !company.isEmpty()) d.company = company;
    }
    public static boolean edited(Document d, String field) {
        return d.edited != null && ("," + d.edited + ",").contains("," + field + ",");
    }
    /** Returns the value typed by the user and, when it differs from the current one, stops automatic extraction for that field. */
    public static String edit(Document d, String field, String current, String value) {
        if (!java.util.Objects.equals(current, value) && !edited(d, field)) d.edited = d.edited == null || d.edited.isEmpty() ? field : d.edited + "," + field;
        return value;
    }
    public static boolean matches(Document d, String query) {
        return normalize(d.title+" "+d.text+" "+d.company+" "+d.nif+" "+d.date+" "+d.total+" "+d.tags+" "+d.barcodes).contains(normalize(query));
    }
    /**
     * Reads a date as the comparable number yyyymmdd. Accepts a full date, a month or a year, and
     * widens the partial ones to the first or to the last day so they can bound a range. Returns
     * {@link #NO_DAY} when there is no usable date.
     */
    public static int day(String value, boolean start) {
        String full = match("\\b(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{4})\\b", value);
        if (!full.isEmpty()) { String[] parts = full.split("[/.-]"); return number(parts[2])*10000 + number(parts[1])*100 + number(parts[0]); }
        String month = match("\\b(\\d{1,2}[/.-]\\d{4})\\b", value);
        if (!month.isEmpty()) { String[] parts = month.split("[/.-]"); return number(parts[1])*10000 + number(parts[0])*100 + (start ? 1 : 31); }
        String year = match("\\b(\\d{4})\\b", value);
        if (!year.isEmpty()) return number(year)*10000 + (start ? 101 : 1231);
        return NO_DAY;
    }
    public static final int NO_DAY = -1;
    public static final long NO_AMOUNT = Long.MIN_VALUE;
    /**
     * Reads an amount as cents, accepting both the Portuguese and the English separators. The last
     * separator followed by one or two digits is the decimal one; any other is a thousands grouping.
     * Returns {@link #NO_AMOUNT} when there is no usable number.
     */
    public static long cents(String value) {
        String cleaned = value.replaceAll("[^0-9,.]", "");
        if (cleaned.isEmpty()) return NO_AMOUNT;
        int separator = Math.max(cleaned.lastIndexOf(','), cleaned.lastIndexOf('.'));
        int decimals = separator < 0 ? 0 : cleaned.length()-separator-1;
        String whole = decimals >= 1 && decimals <= 2 ? cleaned.substring(0, separator) : cleaned;
        String fraction = decimals >= 1 && decimals <= 2 ? cleaned.substring(separator+1) : "";
        whole = whole.replaceAll("[^0-9]", "");
        if (whole.isEmpty() && fraction.isEmpty()) return NO_AMOUNT;
        if (whole.isEmpty()) whole = "0";
        while (fraction.length() < 2) fraction += "0";
        try { return Long.parseLong(whole)*100 + Long.parseLong(fraction); } catch (NumberFormatException broken) { return NO_AMOUNT; }
    }
    private static int number(String value) { return Integer.parseInt(value); }
    public static String ftsQuery(String input){
        StringBuilder query=new StringBuilder();
        for(String token:normalize(input).split("[^\\p{L}\\p{N}]+")){if(token.isEmpty())continue;if(query.length()>0)query.append(' ');query.append(token).append('*');}
        return query.toString();
    }
}
