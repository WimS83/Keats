/*
 * The MIT License
 *
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.sf.samtools;


import net.sf.samtools.seekablestream.SeekableBufferedStream;
import net.sf.samtools.seekablestream.SeekableHTTPStream;
import net.sf.samtools.seekablestream.SeekableStream;
import net.sf.samtools.util.*;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Class for reading and querying SAM/BAM files.  Delegates to appropriate concrete implementation.
 */
public class SAMFileReader implements Iterable<SAMRecord>, Closeable {

    private static ValidationStringency defaultValidationStringency = ValidationStringency.DEFAULT_STRINGENCY;

    public static ValidationStringency getDefaultValidationStringency() {
        return defaultValidationStringency;
    }

    /**
     * Set validation stringency for all subsequently-created SAMFileReaders.  This is the only way to
     * change the validation stringency for SAM header.
     * NOTE: Programs that change this should make sure to have a try/finally clause wrapping the work that
     * they do, so that the original stringency can be restored after the program's work is done.  This facilitates
     * calling a program that is usually run stand-alone from another program, without messing up the original
     * validation stringency.
     */
    public static void setDefaultValidationStringency(final ValidationStringency defaultValidationStringency) {
        SAMFileReader.defaultValidationStringency = defaultValidationStringency;
    }

	/**
	 * Returns the SAMSequenceDictionary from the provided FASTA.
	 */
	public static SAMSequenceDictionary getSequenceDictionary(final File dictionaryFile) {
		final SAMFileReader samFileReader = new SAMFileReader(dictionaryFile);
		final SAMSequenceDictionary dict = samFileReader.getFileHeader().getSequenceDictionary();
		CloserUtil.close(dictionaryFile);
		return dict;
	}

    private boolean mIsBinary = false;
    private BAMIndex mIndex = null;
    private SAMRecordFactory samRecordFactory = new DefaultSAMRecordFactory();
    private ReaderImplementation mReader = null;

    private File samFile = null;

    /**
     * How strict to be when reading a SAM or BAM, beyond bare minimum validation.
     */
    public enum ValidationStringency {
        /**
         * Do the right thing, throw an exception if something looks wrong.
         */
        STRICT,
        /**
         * Emit warnings but keep going if possible.
         */
        LENIENT,
        /**
         * Like LENIENT, only don't emit warning messages.
         */
        SILENT;

        public static final ValidationStringency DEFAULT_STRINGENCY = STRICT;
    }

    /**
     * Internal interface for SAM/BAM file reader implementations.
     * Implemented as an abstract class to enforce better access control.
     */
    static abstract class ReaderImplementation {
        abstract void enableFileSource(final SAMFileReader reader, final boolean enabled);
        abstract void enableIndexCaching(final boolean enabled);
        abstract void enableIndexMemoryMapping(final boolean enabled);
        abstract void enableCrcChecking(final boolean enabled);
        abstract void setSAMRecordFactory(final SAMRecordFactory factory);
        abstract boolean hasIndex();
        abstract BAMIndex getIndex();
        abstract SAMFileHeader getFileHeader();
        abstract CloseableIterator<SAMRecord> getIterator();
        abstract CloseableIterator<SAMRecord> getIterator(SAMFileSpan fileSpan);
        abstract SAMFileSpan getFilePointerSpanningReads();
        abstract CloseableIterator<SAMRecord> query(String sequence, int start, int end, boolean contained);
        abstract CloseableIterator<SAMRecord> query(QueryInterval[] intervals, boolean contained);
        abstract CloseableIterator<SAMRecord> queryAlignmentStart(String sequence, int start);
        abstract public CloseableIterator<SAMRecord> queryUnmapped();
        abstract void close();
        // If true, emit warnings about format errors rather than throwing exceptions;
        abstract void setValidationStringency(final ValidationStringency validationStringency);
        abstract ValidationStringency getValidationStringency();
    }


    /**
     * Prepare to read a SAM or BAM file.  Indexed lookup not allowed because reading from InputStream.
     */
    public SAMFileReader(final InputStream stream) {
        this(stream, false);
    }

    /**
     * Prepare to read a SAM or BAM file.  If the given file is a BAM, and has a companion BAI index file
     * that is named according to the convention, it will be found and opened, and indexed query will be allowed.
     */
    public SAMFileReader(final File file) {
        this(file, null, false);
    }

    /**
     * Prepare to read a SAM or BAM file.  If the given file is a BAM, and an index is present, indexed query
     * will be allowed.
     *
     * @param file SAM or BAM to read.
     * @param indexFile Index file that is companion to BAM, or null if no index file, or if index file
     * should be found automatically.
     */
    public SAMFileReader(final File file, final File indexFile) {
        this(file, indexFile, false);
    }

    /**
     * Read a SAM or BAM file.  Indexed lookup not allowed because reading from InputStream.
     *
     * @param stream input SAM or BAM.  This is buffered internally so caller need not buffer.
     * @param eagerDecode if true, decode SAM record entirely when reading it.
     */
    public SAMFileReader(final InputStream stream, final boolean eagerDecode) {
        init(stream, null, null, eagerDecode, defaultValidationStringency);
    }

    /**
     * Read a SAM or BAM file, possibly with an index file if present.
     * If the given file is a BAM, and an index is present, indexed query will be allowed.
     *
     * @param file SAM or BAM.
     * @param eagerDecode if true, decode SAM record entirely when reading it.
     */
    public SAMFileReader(final File file, final boolean eagerDecode) {
        this(file, null, eagerDecode);
    }

    /**
     * Read a SAM or BAM file, possibly with an index file. If the given file is a BAM, and an index is present,
     * indexed query will be allowed.
     *
     * @param file SAM or BAM.
     * @param indexFile Location of index file, or null in order to use the default index file (if present).
     * @param eagerDecode eagerDecode if true, decode SAM record entirely when reading it.
     */
    public SAMFileReader(final File file, final File indexFile, final boolean eagerDecode){
        init(null, file, indexFile, eagerDecode, defaultValidationStringency);
    }

    /**
     * Read a BAM file by http
     * indexed query will be allowed.
     *
     * @param url         BAM.
     * @param indexFile   Location of index file, or null if indexed access not required.
     * @param eagerDecode eagerDecode if true, decode SAM record entirely when reading it.
     */
    public SAMFileReader(final URL url, final File indexFile, final boolean eagerDecode) {
        init(new SeekableBufferedStream(new SeekableHTTPStream(url)),
                indexFile, eagerDecode, defaultValidationStringency);
    }

    /**
     * Read a BAM file via caller-supplied mechanism.  Indexed query will be allowed, but
     * index file must be provided in that case.
     * @param strm BAM -- If the stream is not buffered, caller should wrap in SeekableBufferedStream for
     * better performance.
     * @param indexFile Location of index file, or null indexed access not required.
     * @param eagerDecode if true, decode SAM record entirely when reading it.
     */
    public SAMFileReader(final SeekableStream strm, final File indexFile, final boolean eagerDecode) {
        init(strm, indexFile, eagerDecode, defaultValidationStringency);
    }

    /**
     * @param strm BAM -- If the stream is not buffered, caller should wrap in SeekableBufferedStream for
     * better performance.
     */
    public SAMFileReader(final SeekableStream strm, final SeekableStream indexStream, final boolean eagerDecode) {
        init(strm, indexStream, eagerDecode, defaultValidationStringency);
    }

    public void close() {
        if (mReader != null) {
            mReader.close();
        }
        mReader = null;
        mIndex = null;
    }

    /**
     * If true, writes the source of every read into the source SAMRecords.
     * @param enabled true to write source information into each SAMRecord. 
     */
    public void enableFileSource(final boolean enabled) {
        mReader.enableFileSource(this,enabled);
    }

    /**
     * If true, uses the caching version of the index reader.
     * @param enabled true to write source information into each SAMRecord.
     */
    public void enableIndexCaching(final boolean enabled) {
        if(mIndex != null)
            throw new SAMException("Unable to turn on index caching; index file has already been loaded.");
        mReader.enableIndexCaching(enabled);
    }

    /**
     * If false, disable the use of memory mapping for accessing index files (default behavior is to use memory mapping).
     * This is slower but more scalable when accessing large numbers of BAM files sequentially.
     * @param enabled True to use memory mapping, false to use regular I/O.
     */
    public void enableIndexMemoryMapping(final boolean enabled) {
        if (mIndex != null) {
            throw new SAMException("Unable to change index memory mapping; index file has already been loaded.");
        }
        mReader.enableIndexMemoryMapping(enabled);
    }

    /**
     * Only meaningful for BAM file readers - enables or disables checking of checksums on uncompressed
     * data during decompression. Enabling this will increase decompression time by 15-30%.
     */
    public void enableCrcChecking(final boolean enabled) {
        this.mReader.enableCrcChecking(enabled);
    }

    /**
     * Override the default SAMRecordFactory class used to instantiate instances of SAMRecord and BAMRecord.
     */
    public void setSAMRecordFactory(final SAMRecordFactory factory) {
        this.samRecordFactory = factory;
        this.mReader.setSAMRecordFactory(factory);
    }

    /**
     * @return True if this is a BAM reader.
     */
    public boolean isBinary() {
        return mIsBinary;
    }

    /**
     * @return true if ths is a BAM file, and has an index
     */
    public boolean hasIndex() {
        return mReader.hasIndex();
    }

    /**
     * Retrieves the index for the given file type.  Ensure that the index is of the specified type.
     * @return An index of the given type.
     */
    public BAMIndex getIndex() {
        return mReader.getIndex();
    }

    /**
     * Returns true if the supported index is browseable, meaning the bins in it can be traversed
     * and chunk data inspected and retrieved.
     * @return True if the index supports the BrowseableBAMIndex interface.  False otherwise.
     */
    public boolean hasBrowseableIndex() {
        return hasIndex() && getIndex() instanceof BrowseableBAMIndex;
    }

    /**
     * Gets an index tagged with the BrowseableBAMIndex interface.  Throws an exception if no such
     * index is available.
     * @return An index with a browseable interface, if possible.
     * @throws SAMException if no such index is available.
     */
    public BrowseableBAMIndex getBrowseableIndex() {
        final BAMIndex index = getIndex();
        if(!(index instanceof BrowseableBAMIndex))
            throw new SAMException("Cannot return index: index created by BAM is not browseable.");
        return BrowseableBAMIndex.class.cast(index);
    }

    public SAMFileHeader getFileHeader() {
        return mReader.getFileHeader();
    }

    /**
     * Control validation of SAMRecords as they are read from file.
     * In order to control validation stringency for SAM Header, call SAMFileReader.setDefaultValidationStringency
     * before constructing a SAMFileReader.
     */
    public void setValidationStringency(final ValidationStringency validationStringency) {
        mReader.setValidationStringency(validationStringency);
    }

    /**
     * Iterate through file in order.  For a SAMFileReader constructed from an InputStream, and for any SAM file,
     * a 2nd iteration starts where the 1st one left off.  For a BAM constructed from a File, each new iteration
     * starts at the first record.
     * <p/>
     * Only a single open iterator on a SAM or BAM file may be extant at any one time.  If you want to start
     * a second iteration, the first one must be closed first.
     */
    public SAMRecordIterator iterator() {
        return new AssertableIterator(mReader.getIterator());
    }

    /**
     * Iterate through the given chunks in the file.
     * @param chunks List of chunks for which to retrieve data.
     * @return An iterator over the given chunks.
     */
    public SAMRecordIterator iterator(final SAMFileSpan chunks) {
        return new AssertableIterator(mReader.getIterator(chunks));
    }

    /**
     * Gets a pointer spanning all reads in the BAM file.
     * @return Unbounded pointer to the first record, in chunk format.
     */
    public SAMFileSpan getFilePointerSpanningReads() {
        return mReader.getFilePointerSpanningReads();
    }

    /**
     * Iterate over records that match the given interval.  Only valid to call this if hasIndex() == true.
     * <p/>
     * Only a single open iterator on a given SAMFileReader may be extant at any one time.  If you want to start
     * a second iteration, the first one must be closed first.  You can use a second SAMFileReader to iterate
     * in parallel over the same underlying file.
     * <p/>
     * Note that indexed lookup is not perfectly efficient in terms of disk I/O.  I.e. some SAMRecords may be read
     * and then discarded because they do not match the interval of interest.
     * <p/>
     * Note that an unmapped read will be returned by this call if it has a coordinate for the purpose of sorting that
     * is in the query region.
     *
     * @param sequence  Reference sequence of interest.
     * @param start     1-based, inclusive start of interval of interest. Zero implies start of the reference sequence.
     * @param end       1-based, inclusive end of interval of interest. Zero implies end of the reference sequence.
     * @param contained If true, each SAMRecord returned is will have its alignment completely contained in the
     *                  interval of interest.  If false, the alignment of the returned SAMRecords need only overlap the interval of interest.
     * @return Iterator over the SAMRecords matching the interval.
     */
    public SAMRecordIterator query(final String sequence, final int start, final int end, final boolean contained) {
        return new AssertableIterator(mReader.query(sequence, start, end, contained));
    }

    /**
     * Iterate over records that overlap the given interval.  Only valid to call this if hasIndex() == true.
     * <p/>
     * Only a single open iterator on a given SAMFileReader may be extant at any one time.  If you want to start
     * a second iteration, the first one must be closed first.
     * <p/>
     * Note that indexed lookup is not perfectly efficient in terms of disk I/O.  I.e. some SAMRecords may be read
     * and then discarded because they do not match the interval of interest.
     * <p/>
     * Note that an unmapped read will be returned by this call if it has a coordinate for the purpose of sorting that
     * is in the query region.
     *
     * @param sequence Reference sequence of interest.
     * @param start    1-based, inclusive start of interval of interest. Zero implies start of the reference sequence.
     * @param end      1-based, inclusive end of interval of interest. Zero implies end of the reference sequence.
     * @return Iterator over the SAMRecords overlapping the interval.
     */
    public SAMRecordIterator queryOverlapping(final String sequence, final int start, final int end) {
        return query(sequence, start, end, false);
    }

    /**
     * Iterate over records that are contained in the given interval.  Only valid to call this if hasIndex() == true.
     * <p/>
     * Only a single open iterator on a given SAMFileReader may be extant at any one time.  If you want to start
     * a second iteration, the first one must be closed first.
     * <p/>
     * Note that indexed lookup is not perfectly efficient in terms of disk I/O.  I.e. some SAMRecords may be read
     * and then discarded because they do not match the interval of interest.
     * <p/>
     * Note that an unmapped read will be returned by this call if it has a coordinate for the purpose of sorting that
     * is in the query region.
     *
     * @param sequence Reference sequence of interest.
     * @param start    1-based, inclusive start of interval of interest. Zero implies start of the reference sequence.
     * @param end      1-based, inclusive end of interval of interest. Zero implies end of the reference sequence.
     * @return Iterator over the SAMRecords contained in the interval.
     */
    public SAMRecordIterator queryContained(final String sequence, final int start, final int end) {
        return query(sequence, start, end, true);
    }

    /**
     * Iterate over records that match one of the given intervals.  This may be more efficient than querying
     * each interval separately, because multiple reads of the same SAMRecords is avoided.
     * <p/>
     * Only valid to call this if hasIndex() == true.
     * <p/>
     * Only a single open iterator on a given SAMFileReader may be extant at any one time.  If you want to start
     * a second iteration, the first one must be closed first.  You can use a second SAMFileReader to iterate
     * in parallel over the same underlying file.
     * <p/>
     * Note that indexed lookup is not perfectly efficient in terms of disk I/O.  I.e. some SAMRecords may be read
     * and then discarded because they do not match an interval of interest.
     * <p/>
     * Note that an unmapped read will be returned by this call if it has a coordinate for the purpose of sorting that
     * is in the query region.
     *
     * @param intervals  Intervals to be queried.  The intervals must be optimized, i.e. in order, with overlapping
     *                   and abutting intervals merged.  This can be done with
     *                   net.sf.samtools.SAMFileReader.QueryInterval#optimizeIntervals(net.sf.samtools.SAMFileReader.QueryInterval[])
     * @param contained If true, each SAMRecord returned is will have its alignment completely contained in one of the
     *                  intervals of interest.  If false, the alignment of the returned SAMRecords need only overlap one of
     *                  the intervals of interest.
     * @return Iterator over the SAMRecords matching the interval.
     */
    public SAMRecordIterator query(final QueryInterval[] intervals, final boolean contained) {
        return new AssertableIterator(mReader.query(intervals, contained));
    }

    /**
     * Iterate over records that overlap any of the given intervals.  This may be more efficient than querying
     * each interval separately, because multiple reads of the same SAMRecords is avoided.
     * <p/>
     * Only valid to call this if hasIndex() == true.
     * <p/>
     * Only a single open iterator on a given SAMFileReader may be extant at any one time.  If you want to start
     * a second iteration, the first one must be closed first.
     * <p/>
     * Note that indexed lookup is not perfectly efficient in terms of disk I/O.  I.e. some SAMRecords may be read
     * and then discarded because they do not match the interval of interest.
     * <p/>
     * Note that an unmapped read will be returned by this call if it has a coordinate for the purpose of sorting that
     * is in the query region.
     *
     * @param intervals  Intervals to be queried.  The intervals must be optimized, i.e. in order, with overlapping
     *                   and abutting intervals merged.  This can be done with
     *                   net.sf.samtools.SAMFileReader.QueryInterval#optimizeIntervals(net.sf.samtools.SAMFileReader.QueryInterval[])
     * @return Iterator over the SAMRecords overlapping any of the intervals.
     */
    public SAMRecordIterator queryOverlapping(final QueryInterval[] intervals) {
        return query(intervals, false);
    }

    /**
     * Iterate over records that are contained in the given interval.  This may be more efficient than querying
     * each interval separately, because multiple reads of the same SAMRecords is avoided.
     * <p/>
     * Only valid to call this if hasIndex() == true.
     * <p/>
     * Only a single open iterator on a given SAMFileReader may be extant at any one time.  If you want to start
     * a second iteration, the first one must be closed first.
     * <p/>
     * Note that indexed lookup is not perfectly efficient in terms of disk I/O.  I.e. some SAMRecords may be read
     * and then discarded because they do not match the interval of interest.
     * <p/>
     * Note that an unmapped read will be returned by this call if it has a coordinate for the purpose of sorting that
     * is in the query region.
     *
     * @param intervals  Intervals to be queried.  The intervals must be optimized, i.e. in order, with overlapping
     *                   and abutting intervals merged.  This can be done with
     *                   net.sf.samtools.SAMFileReader.QueryInterval#optimizeIntervals(net.sf.samtools.SAMFileReader.QueryInterval[])
     * @return Iterator over the SAMRecords contained in any of the intervals.
     */
    public SAMRecordIterator queryContained(final QueryInterval[] intervals) {
        return query(intervals, true);
    }


    public SAMRecordIterator queryUnmapped() {
        return new AssertableIterator(mReader.queryUnmapped());
    }

    /**
     * Iterate over records that map to the given sequence and start at the given position.  Only valid to call this if hasIndex() == true.
     * <p/>
     * Only a single open iterator on a given SAMFileReader may be extant at any one time.  If you want to start
     * a second iteration, the first one must be closed first.
     * <p/>
     * Note that indexed lookup is not perfectly efficient in terms of disk I/O.  I.e. some SAMRecords may be read
     * and then discarded because they do not match the interval of interest.
     * <p/>
     * Note that an unmapped read will be returned by this call if it has a coordinate for the purpose of sorting that
     * matches the arguments.
     *
     * @param sequence Reference sequence of interest.
     * @param start    Alignment start of interest.
     * @return Iterator over the SAMRecords with the given alignment start.
     */
    public SAMRecordIterator queryAlignmentStart(final String sequence, final int start) {
        return new AssertableIterator(mReader.queryAlignmentStart(sequence, start));
    }

    /**
     * Fetch the mate for the given read.  Only valid to call this if hasIndex() == true.
     * This will work whether the mate has a coordinate or not, so long as the given read has correct
     * mate information.  This method iterates over the SAM file, so there may not be an unclosed
     * iterator on the SAM file when this method is called.
     *
     * Note that it is not possible to call queryMate when iterating over the SAMFileReader, because queryMate
     * requires its own iteration, and there cannot be two simultaneous iterations on the same SAMFileReader.  The
     * work-around is to open a second SAMFileReader on the same input file, and call queryMate on the second
     * reader.
     *
     * @param rec Record for which mate is sought.  Must be a paired read.
     * @return rec's mate, or null if it cannot be found.
     */
    public SAMRecord queryMate(final SAMRecord rec) {
        if (!rec.getReadPairedFlag()) {
            throw new IllegalArgumentException("queryMate called for unpaired read.");
        }
        if (rec.getFirstOfPairFlag() == rec.getSecondOfPairFlag()) {
            throw new IllegalArgumentException("SAMRecord must be either first and second of pair, but not both.");
        }
        final boolean firstOfPair = rec.getFirstOfPairFlag();
        final CloseableIterator<SAMRecord> it;
        if (rec.getMateReferenceIndex() == SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX) {
            it = queryUnmapped();
        } else {
            it = queryAlignmentStart(rec.getMateReferenceName(), rec.getMateAlignmentStart());
        }
        try {
            SAMRecord mateRec = null;
            while (it.hasNext()) {
                final SAMRecord next = it.next();
                if (!next.getReadPairedFlag()) {
                    if (rec.getReadName().equals(next.getReadName())) {
                        throw new SAMFormatException("Paired and unpaired reads with same name: " + rec.getReadName());
                    }
                    continue;
                }
                if (firstOfPair) {
                    if (next.getFirstOfPairFlag()) continue;
                } else {
                    if (next.getSecondOfPairFlag()) continue;
                }
                if (rec.getReadName().equals(next.getReadName())) {
                    if (mateRec != null) {
                        throw new SAMFormatException("Multiple SAMRecord with read name " + rec.getReadName() +
                                " for " + (firstOfPair ? "second" : "first") + " end.");
                    }
                    mateRec = next;
                }
            }
            return mateRec;
        } finally {
            it.close();
        }
    }


    private void init(final SeekableStream strm, final File indexFile, final boolean eagerDecode,
                      final ValidationStringency validationStringency) {

        try {
            if (streamLooksLikeBam(strm)) {
                mIsBinary = true;
                mReader = new BAMFileReader(strm, indexFile, eagerDecode, validationStringency, this.samRecordFactory);
            } else {
                throw new SAMFormatException("Unrecognized file format: " + strm);
            }
            setValidationStringency(validationStringency);
        }
        catch (final IOException e) {
            throw new RuntimeIOException(e);
        }
    }

    private void init(final SeekableStream strm, final SeekableStream indexStream, final boolean eagerDecode,
                      final ValidationStringency validationStringency) {

        try {
            if (streamLooksLikeBam(strm)) {
                mIsBinary = true;
                mReader = new BAMFileReader(strm, indexStream, eagerDecode, validationStringency, this.samRecordFactory);
            } else {
                throw new SAMFormatException("Unrecognized file format: " + strm);
            }
            setValidationStringency(validationStringency);
        }
        catch (final IOException e) {
            throw new RuntimeIOException(e);
        }
    }

    // Its too expensive to examine the remote file to determine type.
    // Rely on file extension.
    private boolean streamLooksLikeBam(final SeekableStream strm) {
        String source = strm.getSource();
        if(source == null) return true;
        source = source.toLowerCase();
        //Source will typically be a file path or URL
        //If it's a URL we require one of the query parameters to be bam file
        return source.endsWith(".bam") || source.contains(".bam?")|| source.contains(".bam&") || source.contains(".bam%26");
    }

    private void init(final InputStream stream, final File file, final File indexFile, final boolean eagerDecode, final ValidationStringency validationStringency) {
        if (stream != null && file != null) throw new IllegalArgumentException("stream and file are mutually exclusive");
        this.samFile = file;

        try {
            final BufferedInputStream bufferedStream;
            // Buffering is required because mark() and reset() are called on the input stream.
            final int bufferSize = Math.max(Defaults.BUFFER_SIZE, BlockCompressedStreamConstants.MAX_COMPRESSED_BLOCK_SIZE);
            if (file != null) bufferedStream = new BufferedInputStream(new FileInputStream(file), bufferSize);
            else bufferedStream = IOUtil.toBufferedStream(stream);
            if (isBAMFile(bufferedStream)) {
                mIsBinary = true;
                if (file == null || !file.isFile()) {
                    // Handle case in which file is a named pipe, e.g. /dev/stdin or created by mkfifo
                    mReader = new BAMFileReader(bufferedStream, indexFile, eagerDecode, validationStringency, this.samRecordFactory);
                } else {
                    bufferedStream.close();
                    mReader = new BAMFileReader(file, indexFile, eagerDecode, validationStringency, this.samRecordFactory);
                }
            } else if (BlockCompressedInputStream.isValidFile(bufferedStream)) {
                mIsBinary = false;
                mReader = new SAMTextReader(new BlockCompressedInputStream(bufferedStream), validationStringency, this.samRecordFactory);
            } else if (isGzippedSAMFile(bufferedStream)) {
                mIsBinary = false;
                mReader = new SAMTextReader(new GZIPInputStream(bufferedStream), validationStringency, this.samRecordFactory);
            } else if (isSAMFile(bufferedStream)) {
                if (indexFile != null) {
                    bufferedStream.close();
                    throw new RuntimeException("Cannot use index file with textual SAM file");
                }
                mIsBinary = false;
                mReader = new SAMTextReader(bufferedStream, file, validationStringency, this.samRecordFactory);
            } else {
                bufferedStream.close();
                throw new SAMFormatException("Unrecognized file format");
            }

            setValidationStringency(validationStringency);
            mReader.setSAMRecordFactory(this.samRecordFactory);
        }
        catch (final IOException e) {
            throw new RuntimeIOException(e);
        }
    }

    /**
     * @param stream stream.markSupported() must be true
     * @return true if this looks like a BAM file.
     */
    private boolean isBAMFile(final InputStream stream)
            throws IOException {
        if (!BlockCompressedInputStream.isValidFile(stream)) {
          return false;
        }
        final int buffSize = BlockCompressedStreamConstants.MAX_COMPRESSED_BLOCK_SIZE;
        stream.mark(buffSize);
        final byte[] buffer = new byte[buffSize];
        readBytes(stream, buffer, 0, buffSize);
        stream.reset();
        final byte[] magicBuf = new byte[4];
        final int magicLength = readBytes(new BlockCompressedInputStream(new ByteArrayInputStream(buffer)), magicBuf, 0, 4);
        return magicLength == BAMFileConstants.BAM_MAGIC.length && Arrays.equals(BAMFileConstants.BAM_MAGIC, magicBuf);
    }

    private static int readBytes(final InputStream stream, final byte[] buffer, final int offset, final int length)
        throws IOException {
        int bytesRead = 0;
        while (bytesRead < length) {
            final int count = stream.read(buffer, offset + bytesRead, length - bytesRead);
            if (count <= 0) {
                break;
            }
            bytesRead += count;
        }
        return bytesRead;
    }

    /**
     * Attempts to check whether the file is a gzipped sam file.  Returns true if it
     * is and false otherwise.
     */
    private boolean isGzippedSAMFile(final BufferedInputStream stream) {
        if (!stream.markSupported()) {
            throw new IllegalArgumentException("Cannot test a stream that doesn't support marking.");
        }
        stream.mark(8000);

        try {
            final GZIPInputStream gunzip = new GZIPInputStream(stream);
            final int ch = gunzip.read();
            return true;
        }
        catch (final IOException ioe) {
            return false;
        }
        finally {
            try {
                stream.reset();
            }
            catch (final IOException ioe) {
                throw new IllegalStateException("Could not reset stream.");
            }
        }
    }

    private boolean isSAMFile(final InputStream stream) {
        // For now, assume every non-binary file is a SAM text file.
        return true;
    }

    @Override
    public String toString() {
        if (this.samFile == null) {
            return getClass().getSimpleName() + "{initialized with stream}";
        } else {
            return getClass().getSimpleName() + "{" + this.samFile.getAbsolutePath() + "}";
        }
    }

    /**
     * Wrapper class to let calls to Iterator return a SAMRecordIterator
     */
    static class AssertableIterator implements SAMRecordIterator {

        private final CloseableIterator<SAMRecord> wrappedIterator;
        private SAMRecord previous = null;
        private SAMRecordComparator comparator = null;

        public AssertableIterator(final CloseableIterator<SAMRecord> iterator) {
            wrappedIterator = iterator;
        }

        public SAMRecordIterator assertSorted(final SAMFileHeader.SortOrder sortOrder) {

            if (sortOrder == null || sortOrder == SAMFileHeader.SortOrder.unsorted) {
                comparator = null;
                return this;
            }

            comparator = sortOrder.getComparatorInstance();
            return this;
        }

        public SAMRecord next() {
            final SAMRecord result = wrappedIterator.next();
            if (comparator != null) {
                if (previous != null) {
                    if (comparator.fileOrderCompare(previous, result) > 0) {
                         throw new IllegalStateException("Records " + previous.getReadName() + " (" +
                             previous.getReferenceName() + ":" + previous.getAlignmentStart() + ") " +
                             "should come after " + result.getReadName() + " (" +
                             result.getReferenceName() + ":" + result.getAlignmentStart() +
                             ") when sorting with " + comparator.getClass().getName());
                     }
                }
                previous = result;
            }
            return result;
        }

        public void close() { wrappedIterator.close(); }
        public boolean hasNext() { return wrappedIterator.hasNext(); }
        public void remove() { wrappedIterator.remove(); }
    }


    /**
     * Interval relative to a reference, for querying a BAM file.
     */
    public static class QueryInterval implements Comparable<QueryInterval> {

        /** Index of reference sequence, based on the sequence dictionary of the BAM file being queried. */
        public final int referenceIndex;
        /** 1-based, inclusive */
        public final int start;
        /** 1-based, inclusive.  If <= 0, implies that the interval goes to the end of the reference sequence */
        public final int end;


        public QueryInterval(final int referenceIndex, final int start, final int end) {
            if (referenceIndex < 0) {
                throw new IllegalArgumentException("Invalid reference index " + referenceIndex);
            }
            this.referenceIndex = referenceIndex;
            this.start = start;
            this.end = end;
        }


        public int compareTo(final QueryInterval other) {
            int comp = this.referenceIndex - other.referenceIndex;
            if (comp != 0) return comp;
            comp = this.start - other.start;
            if (comp != 0) return comp;
            else if (this.end == other.end) return 0;
            else if (this.end == 0) return 1;
            else if (other.end == 0) return -1;
            else return this.end - other.end;
        }

        /**
         * @return true if both are on same reference, and other starts exactly where this ends.
         */
        public boolean abuts(final QueryInterval other) {
            return this.referenceIndex == other.referenceIndex && this.end == other.start;
        }

        /**
         * @return true if both are on same reference, and the overlap.
         */
        public boolean overlaps(final QueryInterval other) {
            if (this.referenceIndex != other.referenceIndex) {
                return false;
            }
            final int thisEnd = (this.end == 0? Integer.MAX_VALUE: this.end);
            final int otherEnd = (other.end == 0? Integer.MAX_VALUE: other.end);
            return CoordMath.overlaps(this.start, thisEnd, other.start, otherEnd);
        }

        @Override
        public String toString() {
            return String.format("%d:%d-%d", referenceIndex, start, end);
        }

        private static final QueryInterval[] EMPTY_QUERY_INTERVAL_ARRAY = new QueryInterval[0];

        /**
         *
         * @param inputIntervals WARNING: This list is modified (sorted) by this method.
         * @return Ordered list of intervals in which abutting and overlapping intervals are merged.
         */
        public static QueryInterval[] optimizeIntervals(final QueryInterval[] inputIntervals) {
            if (inputIntervals.length == 0) return EMPTY_QUERY_INTERVAL_ARRAY;
            Arrays.sort(inputIntervals);

            final List<QueryInterval> unique = new ArrayList<QueryInterval>();
            QueryInterval previous = inputIntervals[0];


            for(int i = 1; i < inputIntervals.length; ++i) {
                final QueryInterval next = inputIntervals[i];
                if (previous.abuts(next) || previous.overlaps(next)) {
                    final int newEnd = ((previous.end == 0 || next.end == 0)? 0: Math.max(previous.end, next.end));
                    previous = new QueryInterval(previous.referenceIndex, previous.start, newEnd);
                }
                else {
                    unique.add(previous);
                    previous = next;
                }
            }

            if (previous != null) unique.add(previous);

            return unique.toArray(EMPTY_QUERY_INTERVAL_ARRAY);
        }
    }

    /**
     * Convenience method to create a QueryInterval
     * @param sequence sequence of interest, must exist in sequence dictionary
     * @param start 1-based start position, must be >= 1
     * @param end 1-based end position.
     * @throws IllegalArgumentException if sequence not found in sequence dictionary, or start position < 1
     */
    public QueryInterval makeQueryInterval(final String sequence, int start, int end) {
        int referenceIndex = getFileHeader().getSequenceIndex(sequence);
        if (referenceIndex < 0) {
            throw new IllegalArgumentException(String.format("Sequence '%s' not found in sequence dictionary", sequence));
        }
        if (start < 1) {
            throw new IllegalArgumentException("Start position must be >= 1");
        }
        return new QueryInterval(referenceIndex, start, end);
    }

    /**
     * Convenience method to create a QueryInterval that goes from start to end of given sequence.
     * @param sequence sequence of interest, must exist in sequence dictionary
     * @param start 1-based start position, must be >= 1
     * @throws IllegalArgumentException if sequence not found in sequence dictionary, or start position < 1
     */
    public QueryInterval makeQueryInterval(final String sequence, int start) {
        return makeQueryInterval(sequence, start, 0);
    }

}
