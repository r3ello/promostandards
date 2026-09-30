package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.SupplierOrderEmail.Attachment;
import com.trophy.promostandards.sync.SupplierOrderService.Line;
import com.trophy.promostandards.sync.SupplierOrderService.Preview;

import java.util.List;

/**
 * Every line of the PO and every engraved piece as one CSV, attached to the PO. The body lists the pieces only
 * up to {@link SupplierOrderEmail#BODY_PIECE_LIMIT} in the whole order; this carries all of them, whatever the count — an
 * order of 500 pieces is 500 × lines rows, a few tens of KB, which no mail client clips.
 *
 * <p>No artwork column: the logo and the preview are one per order line (in practice one per order),
 * so the body links them once rather than repeating a URL on each of 1,000 rows.
 *
 * <p>One row per engraved line, not per piece: a line is what the engraver sets up (text + font), and
 * a flat table sorts and filters in Excel with no unpacking. A piece with no text still gets a row, so
 * the count of pieces in the sheet is the count ordered. Starts with a UTF-8 BOM, or Excel reads
 * "Café" as mojibake.
 */
final class EngravingSheet {

    static final List<String> HEADER = List.of("PO", "Order line", "Item", "Description", "Quantity", "Piece", "Field",
            "Text", "Font");

    private EngravingSheet() {
    }

    static String filename(Preview order) {
        return "PO-" + order.poNumber() + "-engraving.csv";
    }

    /** Pieces across every line that carries the customizer's record. */
    static int pieces(Preview order) {
        return order.lines().stream().filter(l -> l.customization() != null)
                .mapToInt(l -> l.customization().pieces().size()).sum();
    }

    /**
     * Every line of the PO, not only the customized ones: the sheet is what the engraver works from, and
     * a product missing from it reads as a product not ordered (#1051: GM828 × 10 listed, CB35 not).
     * A customized line gives a row per engraved line of each piece; a line with plain properties
     * (Easify) a row per property; a line with nothing to engrave one row, so it is still there.
     *
     * @return the sheet, or null when no line has a piece to engrave
     */
    static Attachment of(Preview order) {
        if (pieces(order) == 0) {
            return null;
        }
        StringBuilder csv = new StringBuilder("﻿");
        row(csv, HEADER);
        int lineNo = 0;
        for (Line line : order.lines()) {
            lineNo++;
            List<String> head = List.of(order.poNumber(), String.valueOf(lineNo),
                    line.partId() == null ? "?" : line.partId(), line.title(), String.valueOf(line.quantity()));
            TrophyItem item = line.customization();
            if (item != null && !item.pieces().isEmpty()) {
                for (TrophyItem.Piece piece : item.pieces()) {
                    String n = String.valueOf(piece.number());
                    if (piece.texts().isEmpty()) {
                        row(csv, head, n, "", "", "");
                    }
                    for (TrophyItem.Text t : piece.texts()) {
                        row(csv, head, n, t.label(), t.text(), t.font() == null ? "" : t.font());
                    }
                }
            } else if (!line.personalization().isEmpty()) {
                line.personalization().forEach((k, v) -> row(csv, head, "", k, v, ""));
            } else {
                row(csv, head, "", "", "", "");
            }
        }
        return new Attachment(filename(order), "text/csv", csv.toString());
    }

    private static void row(StringBuilder csv, List<String> head, String... rest) {
        List<String> all = new java.util.ArrayList<>(head);
        all.addAll(List.of(rest));
        row(csv, all);
    }

    /** RFC 4180: every field quoted, quotes doubled, CRLF — text a shopper typed can hold any of them. */
    private static void row(StringBuilder csv, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            String f = fields.get(i) == null ? "" : fields.get(i);
            csv.append(i == 0 ? "" : ",").append('"').append(f.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
