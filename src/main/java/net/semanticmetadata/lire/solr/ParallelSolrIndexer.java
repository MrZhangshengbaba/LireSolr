/*
 * This file is part of the LIRE project: http://www.semanticmetadata.net/lire
 * LIRE is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * LIRE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LIRE; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * We kindly ask you to refer the any or one of the following publications in
 * any publication mentioning or employing Lire:
 *
 * Lux Mathias, Savvas A. Chatzichristofis. Lire: Lucene Image Retrieval –
 * An Extensible Java CBIR Library. In proceedings of the 16th ACM International
 * Conference on Multimedia, pp. 1085-1088, Vancouver, Canada, 2008
 * URL: http://doi.acm.org/10.1145/1459359.1459577
 *
 * Lux Mathias. Content Based Image Retrieval with LIRE. In proceedings of the
 * 19th ACM International Conference on Multimedia, pp. 735-738, Scottsdale,
 * Arizona, USA, 2011
 * URL: http://dl.acm.org/citation.cfm?id=2072432
 *
 * Mathias Lux, Oge Marques. Visual Information Retrieval using Java and LIRE
 * Morgan & Claypool, 2013
 * URL: http://www.morganclaypool.com/doi/abs/10.2200/S00468ED1V01Y201301ICR025
 *
 * Copyright statement:
 * --------------------
 * (c) 2002-2013 by Mathias Lux (mathias@juggle.at)
 *     http://www.semanticmetadata.net/lire, http://www.lire-project.net
 */

package net.semanticmetadata.lire.solr;

import com.jhlabs.image.DespeckleFilter;
import net.semanticmetadata.lire.imageanalysis.*;
import net.semanticmetadata.lire.indexing.hashing.BitSampling;
import net.semanticmetadata.lire.indexing.parallel.WorkItem;
import net.semanticmetadata.lire.utils.ImageUtils;
import org.apache.commons.codec.binary.Base64;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;

/**
 * This indexing application allows for parallel extraction of global features from multiple image files for
 * use with the LIRE Solr plugin. It basically takes a list of images (ie. created by something like
 * "dir /s /b > list.txt" or "ls [some parameters] > list.txt".
 *
 * use it like:
 * <pre>$> java -jar lire-request-handler.jar -i <infile> [-o <outfile>] [-n <threads>] [-m <max_side_length>] [-f]</pre>
 *
 * Available options are:
 * <ul>
 * <li>-i <infile> … gives a file with a list of images to be indexed, one per line.</li>
 * <li>-o <outfile> ... gives XML file the output is written to. if none is given the outfile is <infile>.xml</li>
 * <li>-n <threads> ... gives the number of threads used for extraction. The number of cores is a good value for that.</li>
 * <li>-m <max-side-length> ... gives a maximum side length for extraction. This option is useful if very larger images are indexed.</li>
 * <li>-f ... forces to overwrite the <outfile>. If the <outfile> already exists and -f is not given, then the operation is aborted.</li>
 * </ul>
 *
 * TODO: Make feature list change-able
 *
 * You then basically need to enrich the file with whatever metadata you prefer and send it to Solr using for instance curl:
 * <pre>curl http://localhost:9000/solr/lire/update  -H "Content-Type: text/xml" --data-binary @extracted_file.xml
 curl http://localhost:9000/solr/lire/update  -H "Content-Type: text/xml" --data-binary "<commit/>"</pre>
 * @author Mathias Lux, mathias@juggle.at on  13.08.2013
 */
public class ParallelSolrIndexer implements Runnable {
//    private static HashMap<Class, String> classToPrefix = new HashMap<Class, String>(5);
    private boolean force = false;
    private static boolean individualFiles = false;
    private static int numberOfThreads = 4;
    Stack<WorkItem> images = new Stack<WorkItem>();
    boolean ended = false;
    int overallCount = 0;
    OutputStream dos = null;
    LinkedList<LireFeature> listOfFeatures;
    File fileList = null;
    File outFile = null;
    private int monitoringInterval = 10;
    private int maxSideLength = 512;
    private boolean isPreprocessing = true;

    public ParallelSolrIndexer() {
        // default constructor.
        listOfFeatures = new LinkedList<LireFeature>();
    }

    /**
     * Sets the number of consumer threads that are employed for extraction
     *
     * @param numberOfThreads
     */
    public static void setNumberOfThreads(int numberOfThreads) {
        ParallelSolrIndexer.numberOfThreads = numberOfThreads;
    }

    public static void main(String[] args) throws IOException {
        BitSampling.readHashFunctions();
        ParallelSolrIndexer e = new ParallelSolrIndexer();

        // parse programs args ...
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-i")) {
                // infile ...
                if ((i + 1) < args.length)
                    e.setFileList(new File(args[i + 1]));
                else printHelp();
            } else if (arg.startsWith("-o")) {
                // out file, if it's not set a single file for each input image is created.
                if ((i + 1) < args.length)
                    e.setOutFile(new File(args[i + 1]));
                else printHelp();
            } else if (arg.startsWith("-m")) {
                // out file
                if ((i + 1) < args.length) {
                    try {
                        int s = Integer.parseInt(args[i + 1]);
                        if (s > 10)
                            e.setMaxSideLength(s);
                    } catch (NumberFormatException e1) {
                        e1.printStackTrace();
                        printHelp();
                    }
                } else printHelp();
            } else if (arg.startsWith("-f")) {
                e.setForce(true);
            } else if (arg.startsWith("-p")) {
                e.setPreprocessing(true);
            } else if (arg.startsWith("-h")) {
                // help
                printHelp();
                System.exit(0);
            } else if (arg.startsWith("-n")) {
                if ((i + 1) < args.length)
                    try {
                        ParallelSolrIndexer.numberOfThreads = Integer.parseInt(args[i + 1]);
                    } catch (Exception e1) {
                        System.err.println("Could not set number of threads to \"" + args[i + 1] + "\".");
                        e1.printStackTrace();
                    }
                else printHelp();
            }
        }
        // check if there is an infile, an outfile and some features to extract.
        if (!e.isConfigured()) {
            printHelp();
        } else {
            e.run();
        }
    }

    private static void printHelp() {
        System.out.println("Help for the ParallelSolrIndexer Class\n" +
                "======================================\n" +
                "This help text is shown if you start the ParallelSolrIndexer with the '-h' option.\n" +
                "\n" +
                "1. Usage\n" +
                "========\n" +
                "$> ParallelSolrIndexer -i <infile> [-o <outfile>] [-n <threads>] [-f] [-p] [-m <max_side_length>]\n" +
                "\n" +
                "Note: if you don't specify an outfile just \".xml\" is appended to the infile for output.\n" +
                "\n" +
                "-n ... number of threads should be something your computer can cope with. default is 4.\n" +
                "-f ... forces overwrite of outfile\n" +
                "-p ... enables image processing before indexing (despeckle, trim white space)\n" +
                "-m ... maximum side length of images when indexed. All bigger files are scaled down. default is 512.");
    }

    public static String arrayToString(int[] array) {
        StringBuilder sb = new StringBuilder(array.length * 8);
        for (int i = 0; i < array.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(Integer.toHexString(array[i]));
        }
        return sb.toString();
    }

    /**
     * Adds a feature to the extractor chain. All those features are extracted from images.
     *
     * @param feature
     */
    public void addFeature(LireFeature feature) {
        listOfFeatures.add(feature);
    }

    /**
     * Sets the file list for processing. One image file per line is fine.
     *
     * @param fileList
     */
    public void setFileList(File fileList) {
        this.fileList = fileList;
    }

    /**
     * Sets the outfile. The outfile has to be in a folder parent to all input images.
     *
     * @param outFile
     */
    public void setOutFile(File outFile) {
        this.outFile = outFile;
    }

    public int getMaxSideLength() {
        return maxSideLength;
    }

    public void setMaxSideLength(int maxSideLength) {
        this.maxSideLength = maxSideLength;
    }

    private boolean isConfigured() {
        boolean configured = true;
        if (fileList == null || !fileList.exists()) configured = false;
        else if (outFile == null) {
            individualFiles = true;
            // create an outfile ...
//            try {
//                outFile = new File(fileList.getCanonicalPath() + ".xml");
//                System.out.println("Setting out file to " + outFile.getCanonicalFile());
//            } catch (IOException e) {
//                configured = false;
//            }
        } else if (outFile.exists() && !force) {
            System.err.println(outFile.getName() + " already exists. Please delete or choose another outfile.");
            configured = false;
        }
        return configured;
    }

    @Override
    public void run() {
        // check:
        if (fileList == null || !fileList.exists()) {
            System.err.println("No text file with a list of images given.");
            return;
        }
        try {
            if (!individualFiles) {
                dos = new BufferedOutputStream(new FileOutputStream(outFile));
                dos.write("<add>\n".getBytes());
            }
            Thread p = new Thread(new Producer());
            p.start();
            LinkedList<Thread> threads = new LinkedList<Thread>();
            long l = System.currentTimeMillis();
            for (int i = 0; i < numberOfThreads; i++) {
                Thread c = new Thread(new Consumer());
                c.start();
                threads.add(c);
            }
            Thread m = new Thread(new Monitoring());
            m.start();
            for (Iterator<Thread> iterator = threads.iterator(); iterator.hasNext(); ) {
                iterator.next().join();
            }
            long l1 = System.currentTimeMillis() - l;
            System.out.println("Analyzed " + overallCount + " images in " + l1 / 1000 + " seconds, ~" + (overallCount > 0 ? (l1 / overallCount) : "inf.") + " ms each.");
            if (!individualFiles) {
                dos.write("</add>\n".getBytes());
                dos.close();
            }
//            writer.commit();
//            writer.close();
//            threadFinished = true;

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void addFeatures(List features) {
        // original features
        features.add(new PHOG());
        features.add(new ColorLayout());
        features.add(new EdgeHistogram());
        features.add(new JCD());

        // new features
//        features.add(new CEDD());
//        features.add(new ScalableColor());
    }

    public boolean isPreprocessing() {
        return isPreprocessing;
    }

    public void setPreprocessing(boolean isPreprocessing) {
        this.isPreprocessing = isPreprocessing;
    }

    public boolean isForce() {
        return force;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    class Monitoring implements Runnable {
        public void run() {
            long ms = System.currentTimeMillis();
            try {
                Thread.sleep(1000 * monitoringInterval); // wait xx seconds
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            while (!ended) {
                try {
                    // print the current status:
                    long time = System.currentTimeMillis() - ms;
                    System.out.println("Analyzed " + overallCount + " images in " + time / 1000 + " seconds, " + ((overallCount > 0) ? (time / overallCount) : "n.a.") + " ms each (" + images.size() + " images currently in queue).");
                    Thread.sleep(1000 * monitoringInterval); // wait xx seconds
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    class Producer implements Runnable {
        public void run() {
            int tmpSize = 0;
            try {
                BufferedReader br = new BufferedReader(new FileReader(fileList));
                String file = null;
                File next = null;
                while ((file = br.readLine()) != null) {
                    next = new File(file);
                    BufferedImage img = null;
                    try {
                        int fileSize = (int) next.length();
                        byte[] buffer = new byte[fileSize];
                        FileInputStream fis = new FileInputStream(next);
                        fis.read(buffer);
                        String path = next.getCanonicalPath();
                        synchronized (images) {
                            images.add(new WorkItem(path, buffer));
                            tmpSize = images.size();
                            // if the cache is too crowded, then wait.
                            if (tmpSize > 500) images.wait(500);
                            // if the cache is too small, dont' notify.
                            images.notify();
                        }
                    } catch (Exception e) {
                        System.err.println("Could not read image " + file + ": " + e.getMessage());
                    }
                    try {
                        if (tmpSize > 500) Thread.sleep(1000);
//                        else Thread.sleep(2);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
            synchronized (images) {
                ended = true;
                images.notifyAll();
            }
        }
    }

    class Consumer implements Runnable {
        WorkItem tmp = null;
        LinkedList<LireFeature> features = new LinkedList<LireFeature>();
        int count = 0;
        boolean locallyEnded = false;
        StringBuilder sb = new StringBuilder(1024);

        Consumer() {
            addFeatures(features);
        }

        public void run() {
            byte[] myBuffer = new byte[1024 * 1024 * 10];
            int bufferCount = 0;

            while (!locallyEnded) {
                synchronized (images) {
                    // we wait for the stack to be either filled or empty & not being filled any more.
                    while (images.empty() && !ended) {
                        try {
                            images.wait(200);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    // make sure the thread locally knows that the end has come (outer loop)
                    if (images.empty() && ended)
                        locallyEnded = true;
                    // well the last thing we want is an exception in the very last round.
                    if (!images.empty() && !locallyEnded) {
                        tmp = images.pop();
                        count++;
                        overallCount++;
                    }
                }
                try {
                    if (!locallyEnded) {
                        sb.delete(0, sb.length());
                        ByteArrayInputStream b = new ByteArrayInputStream(tmp.getBuffer());

                        // reads the image. Make sure twelve monkeys lib is in the path to read all jpegs and tiffs.
                        BufferedImage read = ImageIO.read(b);

                        // --------< preprocessing >-------------------------
                        // converts color space to INT_RGB
                        BufferedImage img = ImageUtils.createWorkingCopy(read);;
                        if (isPreprocessing) {
                            // despeckle
                            DespeckleFilter df = new DespeckleFilter();
                            img = df.filter(img, null);
                            img = ImageUtils.trimWhiteSpace(img); // trims white space
                        }
                        // --------< / preprocessing >-------------------------

                        if (maxSideLength > 50)
                            img = ImageUtils.scaleImage(img, maxSideLength); // scales image to 512 max sidelength.

                        else if (img.getWidth() < 32 || img.getHeight() < 32) { // image is too small to be worked with, for now I just do an upscale.
                            double scaleFactor = 128d;
                            if (img.getWidth() > img.getHeight()) {
                                scaleFactor = (128d / (double) img.getWidth());
                            } else {
                                scaleFactor = (128d / (double) img.getHeight());
                            }
                            img = ImageUtils.scaleImage(img, ((int) (scaleFactor * img.getWidth())), (int) (scaleFactor*img.getHeight()));
                        }
                        byte[] tmpBytes = tmp.getFileName().getBytes();
                        sb.append("<doc>");
                        sb.append("<field name=\"id\">");
                        sb.append(tmp.getFileName());
                        sb.append("</field>");
                        sb.append("<field name=\"title\">");
                        sb.append(new File(tmp.getFileName()).getName());
                        sb.append("</field>");

                        for (LireFeature feature : features) {
                            String featureCode = FeatureRegistry.getCodeForClass(feature.getClass());
                            if (featureCode != null) {
                                feature.extract(img);
                                String histogramField = FeatureRegistry.codeToFeatureField(featureCode);
                                String hashesField = FeatureRegistry.codeToHashField(featureCode);

                                sb.append("<field name=\"" + histogramField + "\">");
                                sb.append(Base64.encodeBase64String(feature.getByteArrayRepresentation()));
                                sb.append("</field>");
                                sb.append("<field name=\"" + hashesField + "\">");
                                sb.append(arrayToString(BitSampling.generateHashes(feature.getDoubleHistogram())));
                                sb.append("</field>");
                            }
                        }
                        sb.append("</doc>\n");
                        // finally write everything to the stream - in case no exception was thrown..
                        if (!individualFiles) {
                            synchronized (dos) {
                                dos.write(sb.toString().getBytes());
                                dos.flush();
                            }
                        } else {
                            OutputStream mos = new BufferedOutputStream(new FileOutputStream(tmp.getFileName() + "_solr.xml"));
                            mos.write(sb.toString().getBytes());
                            mos.flush();
                            mos.close();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error processing file " + tmp.getFileName());
                    e.printStackTrace();
                }
            }
        }
    }


}
