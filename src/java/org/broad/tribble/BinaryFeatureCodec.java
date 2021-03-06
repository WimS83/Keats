package org.broad.tribble;

import net.sf.samtools.util.CloserUtil;
import net.sf.samtools.util.LocationAware;
import org.broad.tribble.readers.PositionalBufferedStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * Implements common methods of {@link org.broad.tribble.FeatureCodec}s that read from {@link org.broad.tribble.readers.PositionalBufferedStream}s.
 * @author mccowan
 */
abstract public class BinaryFeatureCodec<T extends Feature> implements FeatureCodec<T, PositionalBufferedStream> {
    @Override
    public PositionalBufferedStream makeSourceFromStream(final InputStream bufferedInputStream) {
        if (bufferedInputStream instanceof PositionalBufferedStream)
            return (PositionalBufferedStream) bufferedInputStream;
        else
            return new PositionalBufferedStream(bufferedInputStream);
    }

    /** {@link org.broad.tribble.readers.PositionalBufferedStream} is already {@link net.sf.samtools.util.LocationAware}. */
    @Override
    public LocationAware makeIndexableSourceFromStream(final InputStream bufferedInputStream) {
        return makeSourceFromStream(bufferedInputStream);
    }

    @Override
    public void close(final PositionalBufferedStream source) {
        CloserUtil.close(source);
    }

    @Override
    public boolean isDone(final PositionalBufferedStream source) {
        try {
            return source.isDone();
        } catch (final IOException e) {
            throw new RuntimeException("Failure reading from stream.", e);
        }
    }
}
