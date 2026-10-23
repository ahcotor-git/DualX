package com.dualx.core.plan;

import com.dualx.core.domain.ExportFormat;
import com.dualx.core.domain.PollMode;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.protocol.Team;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 想定编成表（模板下载 / 文件导入）的编解码，XLSX 与 CSV 两条路径共用同一列序与同一套取值规则
 * （契约 {@code downloadPlanTemplate} / {@code importPlan}）。
 *
 * <p><b>为什么不引 POI / commons-csv</b>：这里要的只是「一张两列的纯文本表」——
 * 写出去是表头 + 示例行，读回来是若干行字符串。POI 会拖进一整棵 OOXML 依赖树，
 * 而模板文件本身用 JDK 自带的 {@code java.util.zip} + {@code javax.xml.stream} 就能读写
 * （XLSX 就是一个装着 XML 的 zip）。M5 复盘导出复用同一取向。</p>
 *
 * <p><b>写 XLSX 时用 inlineStr 而不是 sharedStrings</b>：共享字符串表要多写一个部件、
 * 多一条关系，只为省几 KB 重复文本——模板总共十几格，且 inlineStr 是合法 OOXML，
 * Excel、WPS、LibreOffice 都能打开。<b>读的时候两种都认</b>：操作员把模板用 Excel
 * 另存一次，字符串就会被改写成共享表形式。</p>
 *
 * <p><b>取值规则</b>（模板与导入同一套，避免「导出的模板自己导不回来」）：
 * 必需列 {@code 马甲编号} / {@code 队伍}；其余列可整列缺席，缺席即取契约默认
 * （100 / 30 / 30 / AUTO）；队伍认中英文（{@code RED/红}）；空行忽略。</p>
 *
 * <p><b>示例行</b>：模板第 2 行给一行示例。导入时只跳过<b>第一个数据行</b>且必须整行逐格
 * 全等——操作员删不删示例行都能导入，而后面真有人填出一模一样的内容也不会被静默吞掉。</p>
 *
 * <p>纯函数、不碰磁盘、不读时钟：给一段字节要么给出编成行，要么明确说第几行哪里不对。
 * 失败一律抛 {@link ApiErrorCode#VALIDATION_FAILED}（契约：整文件校验通过才落库）。</p>
 */
public final class PlanRosterSheet {

    /** 模板表头。列序即 XLSX 与 CSV 的写入顺序，也是解析时的定位依据。 */
    private static final List<String> HEADERS =
            List.of("马甲编号", "姓名", "队伍", "血量", "弹夹1", "弹夹2", "轮询模式");

    private static final String VEST_NUM = "马甲编号";

    private static final String NAME = "姓名";

    private static final String TEAM = "队伍";

    private static final String HP = "血量";

    private static final String AMMO1 = "弹夹1";

    private static final String AMMO2 = "弹夹2";

    private static final String MODE = "轮询模式";

    /** 模板第 2 行的示例数据；导入时按「第一个数据行整行全等」跳过。 */
    private static final List<String> EXAMPLE =
            List.of("1001", "张三", "RED", "100", "30", "30", "AUTO");

    /** 契约 {@code RosterEntry} 的默认值，与 {@code PlanService} 注释里的口径一致。 */
    private static final int DEFAULT_HP = 100;

    private static final int DEFAULT_AMMO = 30;

    private static final int MIN_VEST_NUM = 1;

    /** 上限取自库约束 {@code ck_plan_roster_vest_num}（65532 = 危险物编号段的下界减一）。 */
    private static final int MAX_VEST_NUM = 65532;

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String CSV_CONTENT_TYPE = "text/csv";

    /** XLSX 里工作表部件的名字形如 {@code xl/worksheets/sheet1.xml}。 */
    private static final Pattern SHEET_PART = Pattern.compile("xl/worksheets/sheet\\d+\\.xml");

    private static final String SHARED_STRINGS_PART = "xl/sharedStrings.xml";

    private PlanRosterSheet() {
    }

    // ── 对外：模板下载 ──────────────────────────────────────────────────────

    /** 供浏览器下载的模板：名字与 MIME 随格式走，中文名不进文件名（Content-Disposition 编码另说）。 */
    public record TemplateFile(String fileName, String contentType, byte[] content) {
    }

    /** 生成导入模板（表头 + 示例行）。{@code format} 为 null 时按契约默认取 XLSX。 */
    public static TemplateFile template(ExportFormat format) {
        ExportFormat chosen = format == null ? ExportFormat.XLSX : format;
        return chosen == ExportFormat.CSV
                ? new TemplateFile("scds-plan-template.csv", CSV_CONTENT_TYPE,
                        write(List.of(HEADERS, EXAMPLE), ExportFormat.CSV))
                : new TemplateFile("scds-plan-template.xlsx", XLSX_CONTENT_TYPE,
                        write(List.of(HEADERS, EXAMPLE), ExportFormat.XLSX));
    }

    // ── 对外：文件导入 ──────────────────────────────────────────────────────

    /**
     * 编成表里的一行。刻意不复用 {@code PlanService.RosterDraft}：那是服务层入参，
     * 本类只负责「文件里写了什么」，怎么落库由服务层决定。
     */
    public record Row(int vestNum, String name, Team team, int hp, int ammo1, int ammo2,
                      PollMode mode) {
    }

    /**
     * 解析导入文件。<b>格式按字节嗅探</b>而不是看扩展名：扩展名来自上传方，随手能改；
     * {@code PK\x03\x04} 是 zip 的魔数，XLSX 必然以它开头，其余一律当 CSV 文本。
     *
     * @return 编成行；至少一行，否则视为「没东西可导」
     * @throws BusinessException {@code VALIDATION_FAILED}，文案带行号（行号按 Excel 的行计）
     */
    public static List<Row> parse(byte[] content) {
        List<List<String>> grid = grid(content);
        if (grid.isEmpty()) {
            throw invalid(1, "模板里没有内容：第 1 行应为表头 " + String.join("/", HEADERS));
        }
        Map<String, Integer> columns = columnsOf(grid.get(0));
        int vestColumn = required(columns, VEST_NUM);
        int teamColumn = required(columns, TEAM);

        List<Row> rows = new ArrayList<>();
        boolean firstDataRow = true;
        for (int index = 1; index < grid.size(); index++) {
            List<String> cells = grid.get(index);
            int line = index + 1;                      // 表在 Excel 里从第 1 行开始
            if (isBlank(cells)) {
                continue;
            }
            if (firstDataRow) {
                firstDataRow = false;
                if (EXAMPLE.equals(padded(cells))) {
                    continue;
                }
            }
            rows.add(row(cells, line, columns, vestColumn, teamColumn));
        }
        if (rows.isEmpty()) {
            throw invalid(2, "模板里没有可导入的编成行：除示例行外没有数据");
        }
        return rows;
    }

    // ── 列定位与取值 ────────────────────────────────────────────────────────

    /** 表头文本 → 列下标。空表头格忽略；同名列表头取先出现的那一列。 */
    private static Map<String, Integer> columnsOf(List<String> header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < header.size(); index++) {
            String title = header.get(index).trim();
            if (!title.isEmpty()) {
                columns.putIfAbsent(title, index);
            }
        }
        return columns;
    }

    private static int required(Map<String, Integer> columns, String title) {
        Integer column = columns.get(title);
        if (column == null) {
            throw invalid(1, "表头缺少必需列：" + title + "（第 1 行应为 " + String.join("/", HEADERS) + "）");
        }
        return column;
    }

    private static Row row(List<String> cells, int line, Map<String, Integer> columns,
                           int vestColumn, int teamColumn) {
        int vestNum = vestNum(cell(cells, vestColumn), line);
        Team team = team(requiredCell(cells, teamColumn, TEAM, line), line);
        return new Row(vestNum,
                blankToNull(cell(cells, columns.getOrDefault(NAME, -1))),
                team,
                optionalInt(cells, columns, HP, DEFAULT_HP, line),
                optionalInt(cells, columns, AMMO1, DEFAULT_AMMO, line),
                optionalInt(cells, columns, AMMO2, DEFAULT_AMMO, line),
                optionalMode(cells, columns, line));
    }

    /** 列缺席或单元格为空 → 空串，交由各自的解析函数决定「空」算不算错。 */
    private static String cell(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index).trim() : "";
    }

    private static String requiredCell(List<String> cells, int index, String title, int line) {
        String value = cell(cells, index);
        if (value.isEmpty()) {
            throw invalid(line, title + "不能为空");
        }
        return value;
    }

    private static int vestNum(String text, int line) {
        int value = integer(text, line, VEST_NUM);
        if (value < MIN_VEST_NUM || value > MAX_VEST_NUM) {
            throw invalid(line, VEST_NUM + "超出范围（" + MIN_VEST_NUM + "~" + MAX_VEST_NUM + "）：" + text);
        }
        return value;
    }

    /**
     * Excel 把数值格存成 {@code 1001.0} 这种形式，尾随的 {@code .0} 不是操作员填的内容，
     * 故先去掉再用十进制解析；其余情况（{@code 1e3}、{@code 十二}）一律按非法处理。
     */
    private static int integer(String text, int line, String title) {
        String trimmed = text.trim();
        if (trimmed.endsWith(".0")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            throw invalid(line, title + "不是整数：" + text);
        }
    }

    private static int optionalInt(List<String> cells, Map<String, Integer> columns, String title,
                                   int fallback, int line) {
        Integer column = columns.get(title);
        String value = column == null ? "" : cell(cells, column);
        return value.isEmpty() ? fallback : integer(value, line, title);
    }

    private static Team team(String text, int line) {
        Team parsed = switch (normalize(text)) {
            case "RED", "红" -> Team.RED;
            case "BLUE", "蓝" -> Team.BLUE;
            case "ORANGE", "橙" -> Team.ORANGE;
            case "YELLOW", "黄" -> Team.YELLOW;
            default -> null;
        };
        if (parsed == null) {
            throw invalid(line, TEAM + "无法识别：" + text
                    + "（可填 RED/BLUE/ORANGE/YELLOW 或 红/蓝/橙/黄）");
        }
        return parsed;
    }

    private static PollMode optionalMode(List<String> cells, Map<String, Integer> columns, int line) {
        Integer column = columns.get(MODE);
        String value = column == null ? "" : cell(cells, column);
        if (value.isEmpty()) {
            return PollMode.AUTO;
        }
        return switch (normalize(value)) {
            case "AUTO", "自动" -> PollMode.AUTO;
            case "MANUAL", "手动" -> PollMode.MANUAL;
            default -> throw invalid(line, MODE + "无法识别：" + value + "（可填 AUTO/MANUAL 或 自动/手动）");
        };
    }

    /** 大小写无关，并去掉「红队」「蓝色」这类口语后缀。 */
    private static String normalize(String text) {
        String trimmed = text.trim().toUpperCase(Locale.ROOT);
        while (trimmed.endsWith("队") || trimmed.endsWith("色")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isBlank(List<String> cells) {
        return cells.stream().allMatch(String::isEmpty);
    }

    /** 补齐到表头宽度后比较：CSV 尾随空列会被省掉，XLSX 的空格也会被跳过。 */
    private static List<String> padded(List<String> cells) {
        List<String> padded = new ArrayList<>(cells);
        while (padded.size() < HEADERS.size()) {
            padded.add("");
        }
        return padded.size() == HEADERS.size() ? padded : padded.subList(0, HEADERS.size());
    }

    private static String blankToNull(String text) {
        return text.isEmpty() ? null : text;
    }

    private static BusinessException invalid(int line, String message) {
        return new BusinessException(ApiErrorCode.VALIDATION_FAILED, "第 " + line + " 行：" + message);
    }

    // ── 字节 → 网格 ─────────────────────────────────────────────────────────

    /** 解析成「行 × 列」的纯文本网格，供解析与测试断言（测试用同一入口，免得另写一套解包）。 */
    static List<List<String>> grid(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED, "导入文件是空的");
        }
        if (isXlsx(content)) {
            return gridOfXlsx(content);
        }
        if (looksBinary(content)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED,
                    "无法识别的模板文件：只支持 .xlsx 与 .csv（旧版 .xls 请另存为 .xlsx）");
        }
        return gridOfCsv(content);
    }

    private static boolean isXlsx(byte[] content) {
        return content.length >= 4 && content[0] == 'P' && content[1] == 'K'
                && content[2] == 3 && content[3] == 4;
    }

    /** 文本里出现 NUL 只可能是二进制（.xls、压缩包、图片），继续按 CSV 解只会给出莫名其妙的行号。 */
    private static boolean looksBinary(byte[] content) {
        int limit = Math.min(content.length, 512);
        for (int index = 0; index < limit; index++) {
            if (content[index] == 0) {
                return true;
            }
        }
        return false;
    }

    // ── CSV ─────────────────────────────────────────────────────────────────

    /**
     * 最小 CSV 读写（RFC 4180 子集：逗号分隔、双引号包裹、{@code ""} 转义一个引号、CR/LF 换行）。
     *
     * <p>写出时带 UTF-8 BOM：不带 BOM 的 UTF-8 CSV 被 Windows 版 Excel 按 GBK 打开，
     * 中文表头会变乱码——模板恰恰是给 Excel 填的。读入时把 BOM 当透明字符丢掉。
     */
    static byte[] csv(List<List<String>> rows) {
        StringBuilder text = new StringBuilder("\uFEFF");
        for (List<String> row : rows) {
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) {
                    text.append(',');
                }
                text.append(quote(row.get(index)));
            }
            text.append("\r\n");
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static List<List<String>> gridOfCsv(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character != '"') {
                    field.append(character);
                } else if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else {
                    quoted = false;
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (character == '\r' || character == '\n') {
                if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(character);
            }
        }
        if (!row.isEmpty() || !field.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    // ── XLSX ────────────────────────────────────────────────────────────────

    static byte[] xlsx(List<List<String>> rows) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            part(zip, "[Content_Types].xml", CONTENT_TYPES);
            part(zip, "_rels/.rels", ROOT_RELS);
            part(zip, "xl/workbook.xml", WORKBOOK);
            part(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            part(zip, "xl/worksheets/sheet1.xml", sheetXml(rows));
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException ex) {
            // 全在内存里读写，走到这里只可能是 JVM 级别的意外
            throw new IllegalStateException("生成 XLSX 模板失败", ex);
        }
    }

    static byte[] write(List<List<String>> rows, ExportFormat format) {
        return format == ExportFormat.CSV ? csv(rows) : xlsx(rows);
    }

    private static void part(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String sheetXml(List<List<String>> rows) {
        StringBuilder xml = new StringBuilder(XML_DECLARATION)
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetData>");
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            int line = rowIndex + 1;
            xml.append("<row r=\"").append(line).append("\">");
            List<String> row = rows.get(rowIndex);
            for (int column = 0; column < row.size(); column++) {
                xml.append("<c r=\"").append(columnName(column)).append(line)
                        .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escape(row.get(column)))
                        .append("</t></is></c>");
            }
            xml.append("</row>");
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    /** 列下标 → 列名（0→A、25→Z、26→AA）。 */
    private static String columnName(int index) {
        StringBuilder name = new StringBuilder();
        int value = index;
        do {
            name.insert(0, (char) ('A' + value % 26));
            value = value / 26 - 1;
        } while (value >= 0);
        return name.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static List<List<String>> gridOfXlsx(byte[] content) {
        byte[] sheet = null;
        List<String> shared = List.of();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (SHARED_STRINGS_PART.equals(name)) {
                    shared = sharedStrings(zip.readAllBytes());
                } else if (sheet == null && SHEET_PART.matcher(name).matches()) {
                    sheet = zip.readAllBytes();
                }
            }
        } catch (IOException ex) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED,
                    "模板文件无法解析：XLSX 已损坏（" + ex.getMessage() + "）");
        }
        if (sheet == null) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED,
                    "模板文件无法解析：XLSX 里没有工作表");
        }
        // 共享字符串表可能排在工作表之后，故先收齐两个部件再解表
        return sheetRows(sheet, shared);
    }

    /** {@code <si>} 下可能有多个 {@code <t>}（富文本分段），一律拼接。 */
    private static List<String> sharedStrings(byte[] xml) {
        List<String> values = new ArrayList<>();
        StringBuilder text = null;
        XMLEventReader reader = null;
        try {
            reader = readerOf(xml);
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isStartElement()) {
                    String name = event.asStartElement().getName().getLocalPart();
                    if ("si".equals(name)) {
                        text = new StringBuilder();
                    } else if ("t".equals(name) && text != null) {
                        text.append(reader.getElementText());
                    }
                } else if (event.isEndElement() && "si".equals(event.asEndElement().getName().getLocalPart())
                        && text != null) {
                    values.add(text.toString());
                    text = null;
                }
            }
        } catch (XMLStreamException ex) {
            throw brokenXlsx(ex);
        } finally {
            close(reader);
        }
        return values;
    }

    private static List<List<String>> sheetRows(byte[] xml, List<String> shared) {
        List<List<String>> rows = new ArrayList<>();
        TreeMap<Integer, String> row = new TreeMap<>();
        int rowNumber = 0;
        int column = 0;
        String type = null;
        XMLEventReader reader = null;
        try {
            reader = readerOf(xml);
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isStartElement()) {
                    StartElement start = event.asStartElement();
                    switch (start.getName().getLocalPart()) {
                        case "row" -> {
                            rowNumber = attribute(start, "r", rowNumber + 1);
                            row = new TreeMap<>();
                        }
                        case "c" -> {
                            column = columnIndex(attribute(start, "r"), column + 1);
                            type = attribute(start, "t");
                        }
                        case "v" -> {
                            String raw = reader.getElementText();
                            row.put(column, "s".equals(type) ? sharedValue(raw, shared) : raw);
                        }
                        case "t" -> {
                            if ("inlineStr".equals(type)) {
                                row.put(column, reader.getElementText());
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event.isEndElement()
                        && "row".equals(event.asEndElement().getName().getLocalPart())) {
                    // 空行（极少见）也要占位，否则后面的行号会串位
                    while (rows.size() < rowNumber - 1) {
                        rows.add(List.of());
                    }
                    rows.add(flatten(row));
                }
            }
        } catch (XMLStreamException ex) {
            throw brokenXlsx(ex);
        } finally {
            close(reader);
        }
        return rows;
    }

    /** 读取器建在内存字节数组上，关闭不会失败，也不需要在失败路径上再抛一个异常盖住原因。 */
    private static void close(XMLEventReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // 内存流上的读取器没有需要释放的系统资源
        }
    }

    /**
     * {@code t="s"} 的 {@code <v>} 是共享字符串表下标。越界只可能是文件被改坏了，
     * 这里当作空串——「某格为空」随后会以「必需列为空」的清晰文案被拒，而不是让解析器自己崩。
     */
    private static String sharedValue(String raw, List<String> shared) {
        try {
            int index = Integer.parseInt(raw.trim());
            return index >= 0 && index < shared.size() ? shared.get(index) : "";
        } catch (NumberFormatException ex) {
            return "";
        }
    }

    /** 列引用（{@code B12}）→ 列下标。缺 {@code r} 属性时退化为「上一列 + 1」。 */
    private static int columnIndex(String reference, int fallback) {
        if (reference == null || reference.isEmpty()) {
            return fallback;
        }
        int index = 0;
        int position = 0;
        while (position < reference.length() && Character.isLetter(reference.charAt(position))) {
            index = index * 26 + (Character.toUpperCase(reference.charAt(position)) - 'A' + 1);
            position++;
        }
        return position == 0 ? fallback : index - 1;
    }

    private static List<String> flatten(TreeMap<Integer, String> row) {
        if (row.isEmpty()) {
            return List.of();
        }
        List<String> cells = new ArrayList<>();
        for (int index = 0; index <= row.lastKey(); index++) {
            cells.add(row.getOrDefault(index, ""));
        }
        return cells;
    }

    private static int attribute(StartElement start, String name, int fallback) {
        String value = attribute(start, name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** 取属性原值；不存在返回 null（{@code r} 是可选属性，不是所有生成器都写）。 */
    private static String attribute(StartElement start, String name) {
        javax.xml.stream.events.Attribute attribute = start.getAttributeByName(new QName(name));
        return attribute == null ? null : attribute.getValue();
    }

    private static XMLEventReader readerOf(byte[] xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // 上传的文件来自外部：禁掉 DTD 与外部实体，别让「模板」变成读文件或打内网的入口
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory.createXMLEventReader(new ByteArrayInputStream(xml));
    }

    private static BusinessException brokenXlsx(XMLStreamException ex) {
        return new BusinessException(ApiErrorCode.VALIDATION_FAILED,
                "模板文件无法解析：XLSX 内容不是合法 XML（" + ex.getMessage() + "）");
    }

    private static final String XML_DECLARATION =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";

    private static final String CONTENT_TYPES = XML_DECLARATION + """
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
              <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            </Types>""";

    private static final String ROOT_RELS = XML_DECLARATION + """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>""";

    private static final String WORKBOOK = XML_DECLARATION + """
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets><sheet name="编成" sheetId="1" r:id="rId1"/></sheets>
            </workbook>""";

    private static final String WORKBOOK_RELS = XML_DECLARATION + """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            </Relationships>""";
}
