package org.broad.igv.hic.data;


import org.broad.igv.feature.Chromosome;
import org.broad.igv.feature.ChromosomeImpl;
import org.broad.igv.hic.tools.ExpectedValueCalculation;
import org.broad.igv.hic.tools.Preprocessor;
import org.broad.igv.util.CompressionUtils;
import org.broad.igv.util.stream.IGVSeekableStreamFactory;
import org.broad.tribble.util.LittleEndianInputStream;
import org.broad.tribble.util.SeekableStream;

import java.io.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * @author jrobinso
 * @date Aug 17, 2010
 */
public class DatasetReaderV2 implements DatasetReader {

    private String path;
    private SeekableStream stream;
    private Map<String, Preprocessor.IndexEntry> masterIndex;

    private Dataset dataset = null;
    private int version = -1;

    public DatasetReaderV2(String path) throws IOException {
        this.path = path;
        this.stream = IGVSeekableStreamFactory.getStreamFor(path);
        if (this.stream != null)
        {
            masterIndex = new HashMap<String, Preprocessor.IndexEntry>();
            dataset = new Dataset(this);
            version = 0;
        }
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public Dataset read() throws FileNotFoundException {

        try {
            // Read the header
            LittleEndianInputStream dis = new LittleEndianInputStream(new BufferedInputStream(stream));

            String magicString = dis.readString();
            // TODO confirm magicString equals "HIC"

            version = dis.readInt();

            long masterIndexPos = dis.readLong();

            // Genome id (currently not used)
            String genomeId = dis.readString();

            // Read chromosome dictionary
            int nchrs = dis.readInt();
            Chromosome[] chromosomes = new Chromosome[nchrs];
            for (int i = 0; i < nchrs; i++) {
                String name = dis.readString();
                int size = dis.readInt();
                chromosomes[i] = new ChromosomeImpl(i, name, size);
            }
            dataset.setChromosomes(chromosomes);

            int nBpResolutions = dis.readInt();
            for(int i=0; i<nBpResolutions; i++) {
                int binSize = dis.readInt();
            }

            int nFragResolutions = dis.readInt();
            for(int i=0; i<nFragResolutions; i++) {
                int binSize = dis.readInt();
            }

            if(nFragResolutions > 0) {
                nchrs = dis.readInt();   // Not really neccessary
                for(int i=0; i<nchrs; i++) {
                    String chr = dis.readString();
                    int nSites = dis.readInt();
                    for(int s=0; s<nSites; s++) {
                        int site = dis.readInt();
                    }
                }
            }

            int nHemiFragResolutions = dis.readInt();  // Reserved for future use

            // Read attribute dictionary.  Can contain arbitrary # of attributes as key-value pairs, including version

            int nAttributes = dis.readInt();
            String genome = null;
            for (int i = 0; i < nAttributes; i++) {
                String key = dis.readString();
                String value = dis.readString();
            }
            if (genome == null) {
                genome = inferGenome(chromosomes);
            }
            System.out.println("genome = " + genome);

            readMasterIndex(masterIndexPos, version);


        } catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }


        return dataset;

    }

    /**
     * Infer a genome id by examining chromsome sizes.  This method provided for older hic files that do not have
     * a genome id recorded.
     *
     * @param chromosomes
     * @return
     */
    private String inferGenome(Chromosome[] chromosomes) {
        // Initial implementation -- base on chr 1 size
        int len = -1;
        for (Chromosome chr : chromosomes) {
            if (chr.getName().equals("1") || chr.getName().equals("chr1")) {
                len = chr.getLength();
            } else if (chr.getName().equals("23011544")) {
                return "dmel";
            }
        }

        switch (len) {
            case 249250621:
                return "hg19";   //or b37
            case 247249719:
                return "hg18";
            case 197195432:
                return "mm9";
            default:
                return null;
        }

    }

    @Override
    public int getVersion() {
        return version;
    }

    private Map<String, Preprocessor.IndexEntry> readMasterIndex(long position, int version) throws IOException {
        stream.seek(position);
        byte[] buffer = new byte[4];
        stream.readFully(buffer);
        LittleEndianInputStream dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));
        int nBytes = dis.readInt();

        buffer = new byte[nBytes];
        stream.readFully(buffer);

        dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));
        int nEntries = dis.readInt();
        for (int i = 0; i < nEntries; i++) {
            String key = dis.readString();
            long filePosition = dis.readLong();
            int sizeInBytes = dis.readInt();
            masterIndex.put(key, new Preprocessor.IndexEntry(filePosition, sizeInBytes));
        }

        // Expected values
        int nExpectedValues = dis.readInt();
        for(int i=0; i<nExpectedValues; i++) {
            String key = dis.readString();
            int nValues = dis.readInt();
            for(int j=0; j<nValues; j++) {
                float value = dis.readFloat();
            }
        }

        return masterIndex;

    }


    @Override
    public Matrix readMatrix(String key) throws IOException {
        Preprocessor.IndexEntry idx = masterIndex.get(key);
        if (idx == null) {
            return null;
        }

        byte[] buffer = new byte[idx.size];
        stream.seek(idx.position);
        stream.readFully(buffer);
        LittleEndianInputStream dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));

        int c1 = dis.readInt();
        int c2 = dis.readInt();
        int nZooms = dis.readInt();
        // dataset.setNumberZooms(nZooms);
        Chromosome chr1 = dataset.getChromosomes()[c1];
        Chromosome chr2 = dataset.getChromosomes()[c2];

        MatrixZoomData[] zd = new MatrixZoomData[nZooms];
        for (int i = 0; i < nZooms; i++) {
            zd[i] = new MatrixZoomData(chr1, chr2, this, dis);
        }

        Matrix m = new Matrix(c1, c2, zd);
        return m;
    }

    @Override
    public Block readBlock(int blockNumber, Preprocessor.IndexEntry idx) throws IOException {

        byte[] compressedBytes = new byte[idx.size];
        stream.seek(idx.position);
        stream.readFully(compressedBytes);

        byte[] buffer = CompressionUtils.decompress(compressedBytes);
        LittleEndianInputStream dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));

        int nRecords = dis.readInt();
        ContactRecord[] records = new ContactRecord[nRecords];
        for (int i = 0; i < nRecords; i++) {
            try {
                int bin1 = dis.readInt();
                int bin2 = dis.readInt();
                int counts = dis.readInt();
                records[i] = new ContactRecord(blockNumber, bin1, bin2, counts);
            } catch (EOFException e) {
                nRecords = i;
                ContactRecord[] modifiedRecords = new ContactRecord[nRecords];
                System.arraycopy(records, 0, modifiedRecords, 0, nRecords);
                records = modifiedRecords;
                break;
            }

        }

        Arrays.sort(records);
        return new Block(blockNumber, records);

    }



    /**
     * Return a map of zoom level -> DensityFunction
     *
     * @param les
     * @return
     * @throws IOException
     */
    public static Map<Integer, DensityFunction> readDensities(LittleEndianInputStream les) throws IOException {

        int nZooms = les.readInt();
        Map<Integer, DensityFunction> densityMap = new HashMap<Integer, DensityFunction>();
        // TODO -- Its assumed densities are in number order and indices match resolutions.  This is fragile,
        // encode resolutions in the next round
        for (int i = 0; i < nZooms; i++) {
            ExpectedValueCalculation calc = new ExpectedValueCalculation(les);
            densityMap.put(i, new DensityFunction(calc));
        }

        return densityMap;

    }

}
