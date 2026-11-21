package com.wherelee.base.modules.payment.alipay.bill;

import com.wherelee.base.modules.payment.recon.ChannelBillLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 支付宝对账单解析：从账单文件（zip 或 csv）解析出渠道侧的支付流水。
 *
 * <p>支付宝交易账单 CSV 结构：前几行是汇总信息，随后是表头（含“支付宝交易号/商户订单号/订单金额”），
 * 之后是明细；文件通常是 GBK 编码，这里支持 UTF-8/GBK 自动探测。解析只保留支付成功流水，
 * 退款、关闭等记录不进入支付对账（退款对账后续单独扩展）。</p>
 */
public final class AlipayBillParser {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AlipayBillParser() {
    }

    public static List<ChannelBillLine> parse(byte[] raw, String preferredCharset) {
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        byte[] csv = raw;
        if (isZip(raw)) {
            csv = unzipFirstCsv(raw);
        }
        String text = decode(csv, preferredCharset);
        return parseCsv(text);
    }

    private static boolean isZip(byte[] raw) {
        return raw.length > 2 && raw[0] == 'P' && raw[1] == 'K';
    }

    private static byte[] unzipFirstCsv(byte[] raw) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".csv")) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zip.transferTo(out);
                return out.toByteArray();
            }
            return new byte[0];
        } catch (IOException e) {
            throw new IllegalStateException("支付宝账单 zip 解压失败", e);
        }
    }

    private static String decode(byte[] csv, String preferredCharset) {
        if (csv.length >= 3 && (csv[0] & 0xFF) == 0xEF && (csv[1] & 0xFF) == 0xBB && (csv[2] & 0xFF) == 0xBF) {
            return new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(csv)).toString();
        } catch (Exception ignored) {
            return new String(csv, Charset.forName(preferredCharset));
        }
    }

    private static List<ChannelBillLine> parseCsv(String text) {
        Map<String, ChannelBillLine> lines = new LinkedHashMap<>();
        List<String> header = null;
        int tradeNoIndex = -1;
        int merchantOrderIndex = -1;
        int amountIndex = -1;
        int timeIndex = -1;
        int typeIndex = -1;
        for (String rawLine : text.split("\\r?\\n")) {
            List<String> cells = splitCsv(rawLine);
            if (header == null) {
                int merchant = findColumn(cells, "商户订单号");
                if (merchant >= 0 && findColumn(cells, "订单金额") >= 0) {
                    header = cells;
                    tradeNoIndex = findColumn(header, "支付宝交易号");
                    merchantOrderIndex = merchant;
                    amountIndex = findColumn(header, "订单金额");
                    timeIndex = findColumn(header, "完成时间");
                    typeIndex = findColumn(header, "业务类型");
                }
                continue;
            }
            if (cells.size() <= Math.max(merchantOrderIndex, amountIndex)) {
                continue;
            }
            String merchantOrderNo = cell(cells, merchantOrderIndex);
            if (merchantOrderNo == null || merchantOrderNo.isBlank()) {
                continue;
            }
            String businessType = typeIndex >= 0 ? cell(cells, typeIndex) : null;
            if (businessType != null && businessType.contains("退款")) {
                continue;
            }
            BigDecimal amount = parseYuan(cell(cells, amountIndex));
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            long amountCents = amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            LocalDateTime paidAt = parseTime(timeIndex >= 0 ? cell(cells, timeIndex) : null);
            String tradeNo = tradeNoIndex >= 0 ? cell(cells, tradeNoIndex) : null;
            lines.put(merchantOrderNo, new ChannelBillLine(
                    merchantOrderNo, amountCents, ChannelBillLine.STATUS_PAID, paidAt));
        }
        return new ArrayList<>(lines.values());
    }

    private static int findColumn(List<String> header, String keyword) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i) != null && header.get(i).contains(keyword)) {
                return i;
            }
        }
        return -1;
    }

    private static String cell(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index).trim() : null;
    }

    private static BigDecimal parseYuan(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), TIME);
        } catch (Exception e) {
            return null;
        }
    }

    /** 简单 CSV 拆分：支持双引号包裹与转义双引号。 */
    private static List<String> splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }
}
