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
package net.sf.picard.io;

import net.sf.picard.util.ProcessExecutor;
import net.sf.samtools.util.CloserUtil;
import net.sf.samtools.util.CollectionUtil;
import org.testng.annotations.Test;
import org.testng.Assert;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

public class IoUtilTest {

    private static final File SLURP_TEST_FILE = new File("testdata/net/sf/picard/io/slurptest.txt");
    private static final File EMPTY_FILE = new File("testdata/net/sf/picard/io/empty.txt");;
    private static final File FIVE_SPACES_THEN_A_NEWLINE_THEN_FIVE_SPACES_FILE = new File("testdata/net/sf/picard/io/5newline5.txt");
    private static final List<String> SLURP_TEST_LINES = Arrays.asList("bacon   and rice   ","for breakfast  ","wont you join me");
    private static final String SLURP_TEST_LINE_SEPARATOR = "\n";
    private static final String TEST_FILE_PREFIX = "foo";
    private static final String TEST_FILE_EXTENSIONS[] = { ".txt", ".txt.gz", ".txt.bz2" };
    private static final String TEST_STRING = "bar!";

    @Test
    public void testFileReadingAndWriting() throws IOException
    {
        String randomizedTestString = TEST_STRING + System.currentTimeMillis();
        for (String ext : TEST_FILE_EXTENSIONS)
        {
            File f = File.createTempFile(TEST_FILE_PREFIX, ext);

            OutputStream os = IoUtil.openFileForWriting(f);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os));
            writer.write(randomizedTestString);
            writer.close();

            InputStream is = IoUtil.openFileForReading(f);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line = reader.readLine();
            Assert.assertEquals(randomizedTestString, line);
        }
    }

    @Test(groups={"unix"})
    public void testGetCanonicalPath() throws IOException {
        String tmpPath = System.getProperty("java.io.tmpdir");
        String userName = System.getProperty("user.name");

        if(tmpPath.endsWith(userName)) {
            tmpPath = tmpPath.substring(0, tmpPath.length() - userName.length());
        }

        File tmpDir = new File(tmpPath, userName);
        File actual = new File(tmpDir, "actual.txt");
        ProcessExecutor.execute(new String[]{"touch", actual.getAbsolutePath()});
        File symlink = new File(tmpDir, "symlink.txt");
        ProcessExecutor.execute(new String[]{"ln", "-s", actual.getAbsolutePath(), symlink.getAbsolutePath()});
        File lnDir = new File(tmpDir, "symLinkDir");
        ProcessExecutor.execute(new String[]{"ln", "-s", tmpDir.getAbsolutePath(), lnDir.getAbsolutePath()});
        File lnToActual = new File(lnDir, "actual.txt");
        File lnToSymlink = new File(lnDir, "symlink.txt");


        File files [] = { actual, symlink, lnToActual, lnToSymlink };
        for (File f : files) {
            Assert.assertEquals(IoUtil.getFullCanonicalPath(f), actual.getCanonicalPath());
        }

        actual.delete();
        symlink.delete();
        lnToActual.delete();
        lnToSymlink.delete();
        lnDir.delete();
    }

    @Test
    public void testUtfWriting() throws IOException {
        final String utf8 = new StringWriter().append((char)168).append((char)197).toString();
        for (String ext : TEST_FILE_EXTENSIONS) {
            final File f = File.createTempFile(TEST_FILE_PREFIX, ext);
            f.deleteOnExit();

            final BufferedWriter writer = IoUtil.openFileForBufferedUtf8Writing(f);
            writer.write(utf8);
            CloserUtil.close(writer);

            final BufferedReader reader = IoUtil.openFileForBufferedUtf8Reading(f);
            final String line = reader.readLine();
            Assert.assertEquals(utf8, line, f.getAbsolutePath());

            CloserUtil.close(reader);

        }
    }

    @Test
    public void slurpLinesTest() throws FileNotFoundException {
        Assert.assertEquals(IoUtil.slurpLines(SLURP_TEST_FILE), SLURP_TEST_LINES);
    }

    @Test
    public void slurpWhitespaceOnlyFileTest() throws FileNotFoundException {
        Assert.assertEquals(IoUtil.slurp(FIVE_SPACES_THEN_A_NEWLINE_THEN_FIVE_SPACES_FILE), "     \n     ");
    }
    
    @Test
    public void slurpEmptyFileTest() throws FileNotFoundException {
        Assert.assertEquals(IoUtil.slurp(EMPTY_FILE), "");
    }
    
    @Test
    public void slurpTest() throws FileNotFoundException {
        Assert.assertEquals(IoUtil.slurp(SLURP_TEST_FILE), CollectionUtil.join(SLURP_TEST_LINES, SLURP_TEST_LINE_SEPARATOR));
    }
}
