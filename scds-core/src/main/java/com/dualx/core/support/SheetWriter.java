package com.dualx.core.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 最小 XLSX / CSV 写出（无 POI / commons-csv），想定模板与复盘导出共用同一份实现。
 *
 * <p><b>为什么不引 POI</b>：本仓要写的只是「若干张纯文本表」——表头 + 数据行 + 可选多张工作表。
 * XLSX 就是一个装着 XML 的 zip，用 JDK 自带的 {@code java.util.zip} 就能写；引 POI 会拖进
 * 一整棵 OOXML 依赖树，换来的是本仓用不到的样式、公式与图表（见 decision-register「不引新依赖」，
 * 「M5 的 EXPORT 复用同一取向」）。</p>
 *
 * <p><b>写用 inlineStr、不写 sharedStrings</b>：共享字符串表要多写一个部件、多一条关系，
 * 只为省几 KB 重复文本；inlineStr 是合法 OOXML，Excel / WPS / LibreOffice 都能打开。
 * <b>读侧两种都要认</b>（操作员用 Excel 另存一次就会改成共享表形式），但读是各域自己的解析器
 * （各自有不同的取值规则），故本类只负责写。</p>
 *
 * <p><b>CSV 写出带 UTF-8 BOM</b>：不带 BOM 的 UTF-8 CSV 被 Windows 版 Excel 按 GBK 打开时
 * 中文表头会变乱码。</p>
 *
 * <p>纯函数、不碰磁盘、不读时钟。全在内存里读写，失败只可能是 JVM 级别的意外，
 * 故抛 {@link IllegalStateException} 而不是业务异常。</p>
 */
public final class SheetWriter {

    private SheetWriter() {
    }

    /**
     * 一张工作表。
     *
     * @param name 标签页名（进 {@code workbook.xml}，会做 XML 转义）
     * @param rows 行集合；每行是若干字符串格，行与行之间宽度可以不等
     */
    public record Sheet(String name, List<List<String>> rows) {
    }

    /** 多工作表 XLSX；{@code sheets} 至少一张，顺序即标签页顺序（sheet1 在最左）。 */
    public static byte[] xlsx(List<Sheet> sheets) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            part(zip, "[Content_Types].xml", contentTypes(sheets.size()));
            part(zip, "_rels/.rels", ROOT_RELS);
            part(zip, "xl/workbook.xml", workbook(sheets));
            part(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size()));
            for (int index = 0; index < sheets.size(); index++) {
                part(zip, "xl/worksheets/sheet" + (index + 1) + ".xml",
                        sheetXml(sheets.get(index).rows()));
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成 XLSX 失败", ex);
        }
    }

    /**
     * 单表 CSV（RFC 4180 子集：逗号分隔、双引号包裹、{@code ""} 转义一个引号、CRLF 换行）。
     *
     * <p>CSV 本质上只有一张表，要求多张表的调用方自行先拼成一张长表（例如加一列区分表类型）——
     * 那属于该域的报表口径，不该由本类替它决定。</p>
     */
    public static byte[] csv(List<List<String>> rows) {
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

    private static void part(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes(int sheetCount) {
        StringBuilder xml = new StringBuilder(XML_DECLARATION).append("""
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                """);
        for (int index = 1; index <= sheetCount; index++) {
            xml.append("  <Override PartName=\"/xl/worksheets/sheet").append(index)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
                            + ".spreadsheetml.worksheet+xml\"/>\n");
        }
        return xml.append("</Types>").toString();
    }

    private static String workbook(List<Sheet> sheets) {
        StringBuilder xml = new StringBuilder(XML_DECLARATION).append("""
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                """);
        for (int index = 0; index < sheets.size(); index++) {
            int id = index + 1;
            xml.append("    <sheet name=\"").append(escape(sheets.get(index).name()))
                    .append("\" sheetId=\"").append(id).append("\" r:id=\"rId").append(id).append("\"/>\n");
        }
        return xml.append("  </sheets>\n</workbook>").toString();
    }

    private static String workbookRels(int sheetCount) {
        StringBuilder xml = new StringBuilder(XML_DECLARATION).append("""
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                """);
        for (int index = 1; index <= sheetCount; index++) {
            xml.append("  <Relationship Id=\"rId").append(index)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006"
                            + "/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(index).append(".xml\"/>\n");
        }
        return xml.append("</Relationships>").toString();
    }

    /** 单张工作表的 XML：每格一个 {@code inlineStr}，用 {@code xml:space="preserve"} 保住首尾空格。 */
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

    private static final String XML_DECLARATION =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";

    private static final String ROOT_RELS = XML_DECLARATION + """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>""";
}
