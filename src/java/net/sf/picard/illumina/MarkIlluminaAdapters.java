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

package net.sf.picard.illumina;

import net.sf.picard.PicardException;
import net.sf.picard.cmdline.CommandLineProgram;
import net.sf.picard.cmdline.Option;
import net.sf.picard.cmdline.StandardOptionDefinitions;
import net.sf.picard.cmdline.Usage;
import net.sf.picard.io.IoUtil;
import net.sf.picard.metrics.MetricsFile;
import net.sf.picard.sam.ReservedTagConstants;
import net.sf.picard.util.*;
import net.sf.samtools.*;
import net.sf.samtools.util.CollectionUtil;
import net.sf.samtools.util.SequenceUtil;
import net.sf.samtools.util.StringUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static net.sf.picard.util.IlluminaUtil.IlluminaAdapterPair;

/**
 * Command line program to mark the location of adapter sequences.
 * This also outputs a histogram of metrics describing the clipped bases
 *
 * @author Tim Fennell (adapted by mborkan@broadinstitute.org)
 */
public class MarkIlluminaAdapters extends CommandLineProgram {

    // The following attributes define the command-line arguments
    @Usage
    public String USAGE =
            getStandardUsagePreamble() +  "Reads a SAM or BAM file and rewrites it with new adapter-trimming tags.\n" +
                    "Clear any existing adapter-trimming tags (XT:i:).\n" +
                    "Only works for unaligned files in query-name order.\n"+
                    "Note: This is a utility program and will not be run in the pipeline.\n";

    @Option(shortName= StandardOptionDefinitions.INPUT_SHORT_NAME)
    public File INPUT;

    @Option(doc="If output is not specified, just the metrics are generated",
            shortName= StandardOptionDefinitions.OUTPUT_SHORT_NAME, optional=true)
    public File OUTPUT;

    @Option(doc="Histogram showing counts of bases_clipped in how many reads", shortName="M")
    public File METRICS;

    @Option(doc="The minimum number of bases to match over when clipping single-end reads.")
    public int MIN_MATCH_BASES_SE = ClippingUtility.MIN_MATCH_BASES;

    @Option(doc="The minimum number of bases to match over (per-read) when clipping paired-end reads.")
    public int MIN_MATCH_BASES_PE = ClippingUtility.MIN_MATCH_PE_BASES;

    @Option(doc="The maximum mismatch error rate to tolerate when clipping single-end reads.")
    public double MAX_ERROR_RATE_SE = ClippingUtility.MAX_ERROR_RATE;

    @Option(doc="The maximum mismatch error rate to tolerate when clipping paired-end reads.")
    public double MAX_ERROR_RATE_PE = ClippingUtility.MAX_PE_ERROR_RATE;

    @Option(doc="DEPRECATED. Whether this is a paired-end run. No longer used.", shortName="PE", optional=true)
    public Boolean PAIRED_RUN;

    @Option(doc="Which adapters sequences to attempt to identify and clip.")
    public List<IlluminaAdapterPair> ADAPTERS =
            CollectionUtil.makeList(IlluminaAdapterPair.INDEXED,
                    IlluminaAdapterPair.DUAL_INDEXED,
                    IlluminaAdapterPair.PAIRED_END
                    );

    @Option(doc="For specifying adapters other than standard Illumina", optional=true)
    public String FIVE_PRIME_ADAPTER;
    @Option(doc="For specifying adapters other than standard Illumina", optional=true)
    public String THREE_PRIME_ADAPTER;

    private static final Log log = Log.getInstance(MarkIlluminaAdapters.class);

    // Stock main method
    public static void main(final String[] args) {
        System.exit(new MarkIlluminaAdapters().instanceMain(args));
    }

    @Override
    protected String[] customCommandLineValidation() {
        if ((FIVE_PRIME_ADAPTER != null && THREE_PRIME_ADAPTER == null) || (THREE_PRIME_ADAPTER != null && FIVE_PRIME_ADAPTER == null)) {
            return new String[] {"Either both or neither of THREE_PRIME_ADAPTER and FIVE_PRIME_ADAPTER must be set."};
        }
        else {
            return null;
        }
    }

    @Override
    protected int doWork() {
        IoUtil.assertFileIsReadable(INPUT);
        IoUtil.assertFileIsWritable(METRICS);

        final SAMFileReader in = new SAMFileReader(INPUT);
        final SAMFileHeader.SortOrder order = in.getFileHeader().getSortOrder();
        SAMFileWriter out = null;
        if (OUTPUT != null) {
            IoUtil.assertFileIsWritable(OUTPUT);
            out = new SAMFileWriterFactory().makeSAMOrBAMWriter(in.getFileHeader(), true, OUTPUT);
        }

        final Histogram<Integer> histo = new Histogram<Integer>("clipped_bases", "read_count");

        // Combine any adapters and custom adapter pairs from the command line into an array for use in clipping
        final AdapterPair[] adapters;
        {
            final List<AdapterPair> tmp = new ArrayList<AdapterPair>();
            tmp.addAll(ADAPTERS);
            if (FIVE_PRIME_ADAPTER != null && THREE_PRIME_ADAPTER != null) {
                tmp.add(new CustomAdapterPair(FIVE_PRIME_ADAPTER, THREE_PRIME_ADAPTER));
            }
            adapters = tmp.toArray(new AdapterPair[tmp.size()]);
        }

        ////////////////////////////////////////////////////////////////////////
        // Main loop that consumes reads, clips them and writes them to the output
        ////////////////////////////////////////////////////////////////////////
        final ProgressLogger progress = new ProgressLogger(log, 1000000, "Read");
        final SAMRecordIterator iterator = in.iterator();

        while (iterator.hasNext()) {
            final SAMRecord rec = iterator.next();
            final SAMRecord rec2 = rec.getReadPairedFlag() && iterator.hasNext() ? iterator.next() : null;
            rec.setAttribute(ReservedTagConstants.XT, null);

            // Do the clipping one way for PE and another for SE reads
            if (rec.getReadPairedFlag()) {
                // Assert that the input file is in query name order only if we see some PE reads
                if (order != SAMFileHeader.SortOrder.queryname) {
                    throw new PicardException("Input BAM file must be sorted by queryname");
                }

                if (rec2 == null) throw new PicardException("Missing mate pair for paired read: " + rec.getReadName());
                rec2.setAttribute(ReservedTagConstants.XT, null);

                // Assert that we did in fact just get two mate pairs
                if (!rec.getReadName().equals(rec2.getReadName())){
                    throw new PicardException("Adjacent reads expected to be mate-pairs have different names: " +
                            rec.getReadName() + ", " + rec2.getReadName());
                }

                // establish which of pair is first and which second
                final SAMRecord first, second;

                if (rec.getFirstOfPairFlag() && rec2.getSecondOfPairFlag()){
                    first = rec;
                    second = rec2;
                }
                else if (rec.getSecondOfPairFlag() && rec2.getFirstOfPairFlag()) {
                    first = rec2;
                    second = rec;
                }
                else {
                    throw new PicardException("Two reads with same name but not correctly marked as 1st/2nd of pair: " + rec.getReadName());
                }

                ClippingUtility.adapterTrimIlluminaPairedReads(first, second, MIN_MATCH_BASES_PE, MAX_ERROR_RATE_PE, adapters);
            }
            else {
                ClippingUtility.adapterTrimIlluminaSingleRead(rec, MIN_MATCH_BASES_SE, MAX_ERROR_RATE_SE, adapters);
            }

            // Then output the records, update progress and metrics
            for (final SAMRecord r : new SAMRecord[] {rec, rec2}) {
                if (r != null) {
                    progress.record(r);
                    if (out != null) out.addAlignment(r);

                    final Integer clip = rec.getIntegerAttribute(ReservedTagConstants.XT);
                    if (clip != null) histo.increment(rec.getReadLength() - clip + 1);
                }
            }
        }

        if (out != null) out.close();

        // Lastly output the metrics to file
        final MetricsFile<?,Integer> metricsFile = getMetricsFile();
        metricsFile.setHistogram(histo);
        metricsFile.write(METRICS);

        return 0;
    }

    private class CustomAdapterPair implements AdapterPair {

        final String fivePrime, threePrime, fivePrimeReadOrder;
        final byte[]  fivePrimeBytes, threePrimeBytes, fivePrimeReadOrderBytes;

        private CustomAdapterPair(final String fivePrime, final String threePrime) {
            this.threePrime = threePrime;
            this.threePrimeBytes = StringUtil.stringToBytes(threePrime);

            this.fivePrime = fivePrime;
            this.fivePrimeReadOrder = SequenceUtil.reverseComplement(fivePrime);
            this.fivePrimeBytes = StringUtil.stringToBytes(fivePrime);
            this.fivePrimeReadOrderBytes = StringUtil.stringToBytes(fivePrimeReadOrder);
        }

        public String get3PrimeAdapter(){ return threePrime; }
        public String get5PrimeAdapter(){ return fivePrime; }
        public String get3PrimeAdapterInReadOrder(){ return threePrime; }
        public String get5PrimeAdapterInReadOrder() { return fivePrimeReadOrder; }
        public byte[] get3PrimeAdapterBytes() { return threePrimeBytes; }
        public byte[] get5PrimeAdapterBytes() { return fivePrimeBytes; }
        public byte[] get3PrimeAdapterBytesInReadOrder() { return threePrimeBytes; }
        public byte[] get5PrimeAdapterBytesInReadOrder()  { return fivePrimeReadOrderBytes; }
        public String getName() { return "Custom adapter pair"; }
    }
}
