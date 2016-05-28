package org.mabb.fontverter.opentype;

import org.apache.fontbox.cff.CFFStandardEncoding;
import org.mabb.fontverter.CharsetConverter;
import org.mabb.fontverter.CharsetConverter.GlyphMapping;
import org.mabb.fontverter.io.FontDataInputStream;
import org.mabb.fontverter.io.FontDataOutputStream;

import java.io.IOException;
import java.util.*;

abstract class CmapSubTable {
    public static final int CMAP_RECORD_BYTE_SIZE = 8;

    protected int formatNumber;
    protected long languageId = 0;
    protected byte[] rawReadData;

    private int platformId;
    private int platformEncodingId;
    private long subTableOffset;
    private int[] glyphIdToCharacterCode;
    private Map<Integer, Integer> characterCodeToGlyphId;

    public long getSubTableOffset() {
        return subTableOffset;
    }

    long getLanguageId() {
        return languageId;
    }

    public void setSubTableOffset(long subTableOffset) {
        this.subTableOffset = subTableOffset;
    }

    public byte[] getRecordData() throws IOException {
        FontDataOutputStream writer = new FontDataOutputStream(FontDataOutputStream.OPEN_TYPE_CHARSET);
        writer.writeUnsignedShort(platformId);
        writer.writeUnsignedShort(platformEncodingId);
        writer.writeUnsignedInt((int) subTableOffset);
        return writer.toByteArray();
    }

    public int getPlatformId() {
        return platformId;
    }

    public void setPlatformId(int platformId) {
        this.platformId = platformId;
    }

    public int getPlatformEncodingId() {
        return platformEncodingId;
    }

    public void setEncodingId(int platformEncodingId) {
        this.platformEncodingId = platformEncodingId;
    }

    public abstract byte[] getData() throws IOException;

    public abstract int glyphCount();

    public abstract void readData(FontDataInputStream input) throws IOException;

    public List<GlyphMapping> getGlyphMappings() {
        return new ArrayList<GlyphMapping>();
    }

    protected static class Format4SubTable extends CmapSubTable {
        private static final int FORMAT4_HEADER_SIZE = 16;
        // LinkedHashMap important, for keeping ordering the same for loops
        private Map<Integer, Integer> charCodeToGlyphId = new LinkedHashMap<Integer, Integer>();
        private int length = 0;

        public Format4SubTable() {
            formatNumber = 4;
        }

        @Override
        public byte[] getData() throws IOException {
            FontDataOutputStream writer = new FontDataOutputStream(FontDataOutputStream.OPEN_TYPE_CHARSET);

            writer.writeUnsignedShort((int) formatNumber);
            writer.writeUnsignedShort(getLength());
            writer.writeUnsignedShort((int) getLanguageId());

            writer.writeUnsignedShort(getSegmentCount() * 2);
            writer.writeUnsignedShort(getSearchRange());
            writer.writeUnsignedShort(getEntrySelector());
            writer.writeUnsignedShort(getRangeShift());


            List<Integer> ends = getGlyphEnds();
            List<Integer> starts = getGlyphStarts();
            List<Integer> deltas = getGlyphDeltas();

            for (Integer endEntryOn : ends)
                writer.writeUnsignedShort(endEntryOn);
            // end[] padding
            writer.writeUnsignedShort(65535);

            // 'reservedPad' Set to 0
            writer.writeUnsignedShort(0);

            for (Integer startEntryOn : starts)
                writer.writeUnsignedShort(startEntryOn);
            // start[] padding,
            writer.writeUnsignedShort(65535);

            // idDelta[], delta is glyphId storing
            for (Integer deltaEntryOn : deltas)
                writer.writeUnsignedShort(deltaEntryOn);
            writer.writeUnsignedShort(1);

            // idRangeOffset[] blanks unused
            for (int i = 0; i < getSegmentCount(); i++)
                writer.writeUnsignedInt(0);


            byte[] data = writer.toByteArray();
            setDataHeaderLength(data);
            return data;
        }

        public void readData(FontDataInputStream input) throws IOException {
            int length = input.readUnsignedShort();
            rawReadData = input.readBytes(length - 4);
            input = new FontDataInputStream(rawReadData);

            languageId = input.readUnsignedShort();
            int segmentCount = input.readUnsignedShort() / 2;
            int searchRange = input.readUnsignedShort();
            int entrySelector = input.readUnsignedShort();
            int rangeShift = input.readUnsignedShort();

            int[] charCodeRangeEnds = input.readUnsignedShortArray(segmentCount);
            int reservedPad = input.readUnsignedShort();

            int[] charCodeRangeStarts = input.readUnsignedShortArray(segmentCount);
            int[] idDelta = input.readUnsignedShortArray(segmentCount);
            int[] idRangeOffset = input.readUnsignedShortArray(segmentCount);

            for (int i = 0; i < segmentCount - 1; i++) {
                int start = charCodeRangeStarts[i];
                int end = charCodeRangeEnds[i];
                int delta = idDelta[i];

                for (int charCode = start; charCode <= end; charCode++) {
                    int glyphId = (delta + charCode) % 65536;
                    charCodeToGlyphId.put(charCode, glyphId);
                }
            }

        }

        private void setDataHeaderLength(byte[] data) throws IOException {
            FontDataOutputStream lengthWriter = new FontDataOutputStream(FontDataOutputStream.OPEN_TYPE_CHARSET);
            lengthWriter.writeUnsignedShort(data.length);
            byte[] lengthData = lengthWriter.toByteArray();
            data[2] = lengthData[0];
            data[3] = lengthData[1];
        }

        public int glyphCount() {
            return charCodeToGlyphId.size() + 1;
        }

        private int getSegmentCount() {
            // +1 for padding at end of segment arrays
            return getGlyphEnds().size() + 1;
        }

        private int getSearchRange() {
            double logFloor = Math.floor(log2(getSegmentCount()));
            return (int) (2 * (Math.pow(2, logFloor)));
        }

        private int getEntrySelector() {
            return (int) log2(getSearchRange() / 2);
        }

        private int getRangeShift() {
            return 2 * getSegmentCount() - getSearchRange();
        }

        private double log2(int number) {
            return Math.log(number) / Math.log(2);
        }

        private int getLength() {
            return FORMAT4_HEADER_SIZE + ((charCodeToGlyphId.size()) * 8);
        }

        public void addGlyphMapping(int characterCode, int glyphId) {
            charCodeToGlyphId.put(characterCode, glyphId);
        }

        public List<GlyphMapping> getGlyphMappings() {
            return CharsetConverter.charCodeToGlyphIdsToEncoding(charCodeToGlyphId, CFFStandardEncoding.getInstance());
        }

        private List<Integer> getGlyphDeltas() {
            List<Integer> deltas = new ArrayList<Integer>();

            int lastCharCode = -1;
            for (Map.Entry<Integer, Integer> entryOn : getOrderedCharCodeToGlyphIds()) {
                int curCharCode = entryOn.getKey();
                if (curCharCode != lastCharCode + 1)
                    deltas.add(65536 + entryOn.getValue() - curCharCode);

                lastCharCode = curCharCode;
            }

            return deltas;
        }

        private List<Integer> getGlyphStarts() {
            List<Integer> starts = new ArrayList<Integer>();
            int lastCharCode = -1;

            for (Map.Entry<Integer, Integer> entryOn : getOrderedCharCodeToGlyphIds()) {
                int curCharCode = entryOn.getKey();
                if (curCharCode != lastCharCode + 1)
                    starts.add(curCharCode);

                lastCharCode = curCharCode;
            }

            return starts;
        }

        private List<Integer> getGlyphEnds() {
            List<Integer> ends = new ArrayList<Integer>();
            int lastCharCode = -1;
            List<Map.Entry<Integer, Integer>> entries = getOrderedCharCodeToGlyphIds();
            for (Map.Entry<Integer, Integer> entryOn : entries) {
                int curCharCode = entryOn.getKey();
                if (curCharCode != lastCharCode + 1 && lastCharCode != -1)
                    ends.add(lastCharCode);

                lastCharCode = curCharCode;
            }

            // add last one not caught in loop
            if (entries.size() > 1)
                ends.add(entries.get(entries.size() - 1).getKey());

            return ends;
        }

        private List<Map.Entry<Integer, Integer>> getOrderedCharCodeToGlyphIds() {
            List<Map.Entry<Integer, Integer>> charCodeEntries = new ArrayList<Map.Entry<Integer, Integer>>();
            for (Map.Entry<Integer, Integer> entryOn : charCodeToGlyphId.entrySet())
                charCodeEntries.add(entryOn);

            Collections.sort(charCodeEntries, new Comparator<Map.Entry<Integer, Integer>>() {
                @Override
                public int compare(Map.Entry<Integer, Integer> o1, Map.Entry<Integer, Integer> o2) {
                    return o1.getKey() < o2.getKey() ? -1 : o1.getKey().equals(o2.getKey()) ? 0 : 1;
                }
            });

            return charCodeEntries;
        }
    }

    protected static class Format0SubTable extends CmapSubTable {
        private static final int FORMAT0_HEADER_SIZE = 6 + 256;
        // LinkedHashMap important, for keeping ordering the same for loops
        private LinkedHashMap<Integer, Integer> charCodeToGlyphId = new LinkedHashMap<Integer, Integer>();

        public Format0SubTable() {
            formatNumber = 0;
            for (int i = 0; i < 256; i++)
                charCodeToGlyphId.put(i, 0);
        }

        @Override
        public byte[] getData() throws IOException {
            FontDataOutputStream writer = new FontDataOutputStream(FontDataOutputStream.OPEN_TYPE_CHARSET);

            // kludge for read otf fonts
            if (rawReadData != null) {
                writer.writeUnsignedShort(formatNumber);
                writer.writeUnsignedShort(rawReadData.length + 4);
                writer.write(rawReadData);

                return writer.toByteArray();
            }

            writer.writeUnsignedShort((int) formatNumber);
            writer.writeUnsignedShort(getLength());
            writer.writeUnsignedShort((int) getLanguageId());

            for (Map.Entry<Integer, Integer> entry : charCodeToGlyphId.entrySet()) {
                writer.writeByte(entry.getValue());
            }

            return writer.toByteArray();
        }

        public int glyphCount() {
            return 0;
        }

        private int getLength() {
            // + 1 to size for appearant padding need
            return FORMAT0_HEADER_SIZE;
        }

        public void addGlyphMapping(int characterCode, int glyphId) {
            charCodeToGlyphId.put(characterCode, glyphId);
        }

        public void readData(FontDataInputStream input) throws IOException {
            int length = input.readUnsignedShort();
            rawReadData = input.readBytes(length - 4);
            input = new FontDataInputStream(rawReadData);
        }
    }

    static class Format2SubTable extends CmapSubTable {
        public Format2SubTable() {
            formatNumber = 2;
        }

        public byte[] getData() throws IOException {
            // kludge for read otf fonts
            FontDataOutputStream writer = new FontDataOutputStream(FontDataOutputStream.OPEN_TYPE_CHARSET);
            writer.writeUnsignedShort(formatNumber);
            writer.writeUnsignedShort(rawReadData.length + 4);
            writer.write(rawReadData);

            return writer.toByteArray();
        }

        public int glyphCount() {
            return 0;
        }

        public void readData(FontDataInputStream input) throws IOException {
            int length = input.readUnsignedShort();
            rawReadData = input.readBytes(length - 4);
            input = new FontDataInputStream(rawReadData);

        }
    }

    static class Format6SubTable extends CmapSubTable {
        public Format6SubTable() {
            formatNumber = 6;
        }

        public byte[] getData() throws IOException {
            // kludge for read otf fonts
            FontDataOutputStream writer = new FontDataOutputStream(FontDataOutputStream.OPEN_TYPE_CHARSET);
            writer.writeUnsignedShort(formatNumber);
            writer.writeUnsignedShort(rawReadData.length + 4);
            writer.write(rawReadData);

            return writer.toByteArray();
        }

        public int glyphCount() {
            return 0;
        }

        public void readData(FontDataInputStream input) throws IOException {
            int length = input.readUnsignedShort();
            rawReadData = input.readBytes(length - 4);
            input = new FontDataInputStream(rawReadData);

        }
    }

    static class Format8SubTable extends CmapSubTable {
        public Format8SubTable() {
            formatNumber = 8;
        }

        public byte[] getData() throws IOException {
            // kludge for read otf fonts
            FontDataOutputStream writer = new FontDataOutputStream(FontDataOutputStream.OPEN_TYPE_CHARSET);
            writer.writeUnsignedShort(formatNumber);
            // reserved
            writer.writeUnsignedShort(0);
            writer.writeUnsignedInt(rawReadData.length + 8);
            writer.write(rawReadData);

            return writer.toByteArray();
        }

        public int glyphCount() {
            return 0;
        }

        public void readData(FontDataInputStream input) throws IOException {
            int reserved = input.readUnsignedShort();
            long length = input.readUnsignedInt();
            rawReadData = input.readBytes((int) (length - 8));
            input = new FontDataInputStream(rawReadData);
            languageId = input.readUnsignedInt();
        }
    }

    static class Format10SubTable extends CmapSubTable {
        public Format10SubTable() {
            formatNumber = 10;
        }

        public byte[] getData() throws IOException {
            // kludge for read otf fonts
            FontDataOutputStream writer = new FontDataOutputStream(FontDataOutputStream.OPEN_TYPE_CHARSET);
            writer.writeUnsignedShort(formatNumber);
            // reserved
            writer.writeUnsignedShort(0);
            writer.writeUnsignedInt(rawReadData.length + 8);
            writer.write(rawReadData);

            return writer.toByteArray();
        }

        public int glyphCount() {
            return 0;
        }

        public void readData(FontDataInputStream input) throws IOException {
            int reserved = input.readUnsignedShort();
            long length = input.readUnsignedInt();
            rawReadData = input.readBytes((int) (length - 8));
            input = new FontDataInputStream(rawReadData);
            languageId = input.readUnsignedInt();
        }
    }

    static class Format12SubTable extends CmapSubTable {
        public Format12SubTable() {
            formatNumber = 12;
        }

        public byte[] getData() throws IOException {
            // kludge for read otf fonts
            FontDataOutputStream writer = new FontDataOutputStream(FontDataOutputStream.OPEN_TYPE_CHARSET);
            writer.writeUnsignedShort(formatNumber);
            // reserved
            writer.writeUnsignedShort(0);
            writer.writeUnsignedInt(rawReadData.length + 8);
            writer.write(rawReadData);

            return writer.toByteArray();
        }

        public int glyphCount() {
            return 0;
        }

        public void readData(FontDataInputStream input) throws IOException {
            int reserved = input.readUnsignedShort();
            long length = input.readUnsignedInt();
            rawReadData = input.readBytes((int) (length - 8));
            input = new FontDataInputStream(rawReadData);

            languageId = input.readUnsignedInt();
        }
    }

    static class Format13SubTable extends CmapSubTable {
        public Format13SubTable() {
            formatNumber = 13;
        }

        public byte[] getData() throws IOException {
            // kludge for read otf fonts
            FontDataOutputStream writer = new FontDataOutputStream(FontDataOutputStream.OPEN_TYPE_CHARSET);
            writer.writeUnsignedShort(formatNumber);
            // reserved
            writer.writeUnsignedShort(0);
            writer.writeUnsignedInt(rawReadData.length + 8);
            writer.write(rawReadData);

            return writer.toByteArray();
        }

        public int glyphCount() {
            return 0;
        }

        public void readData(FontDataInputStream input) throws IOException {
            int reserved = input.readUnsignedShort();
            long length = input.readUnsignedInt();
            rawReadData = input.readBytes((int) (length - 8));
            input = new FontDataInputStream(rawReadData);

            languageId = input.readUnsignedInt();
        }
    }

    static class Format14SubTable extends CmapSubTable {
        public Format14SubTable() {
            formatNumber = 14;
        }

        public byte[] getData() throws IOException {
            // kludge for read otf fonts
            FontDataOutputStream writer = new FontDataOutputStream(FontDataOutputStream.OPEN_TYPE_CHARSET);
            writer.writeUnsignedShort(formatNumber);
            writer.writeUnsignedInt(rawReadData.length + 6);
            writer.write(rawReadData);

            return writer.toByteArray();
        }

        public int glyphCount() {
            return 0;
        }

        public void readData(FontDataInputStream input) throws IOException {
            long length = input.readUnsignedInt();
            rawReadData = input.readBytes((int) (length - 6));
            input = new FontDataInputStream(rawReadData);

            languageId = input.readUnsignedInt();
        }
    }
}