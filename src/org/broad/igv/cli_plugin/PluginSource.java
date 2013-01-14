/*
 * Copyright (c) 2007-2012 The Broad Institute, Inc.
 * SOFTWARE COPYRIGHT NOTICE
 * This software and its documentation are the copyright of the Broad Institute, Inc. All rights are reserved.
 *
 * This software is supplied without any warranty or guaranteed support whatsoever. The Broad Institute is not responsible for its use, misuse, or functionality.
 *
 * This software is licensed under the terms of the GNU Lesser General Public License (LGPL),
 * Version 2.1 which is available at http://www.opensource.org/licenses/lgpl-2.1.php.
 */

package org.broad.igv.cli_plugin;

import org.apache.log4j.Logger;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.feature.tribble.CodecFactory;
import org.broad.igv.feature.tribble.IGVBEDCodec;
import org.broad.igv.session.IGVSessionReader;
import org.broad.igv.session.SubtlyImportant;
import org.broad.igv.track.FeatureTrack;
import org.broad.igv.track.Track;
import org.broad.igv.util.FileUtils;
import org.broad.igv.util.RuntimeUtils;
import org.broad.tribble.AsciiFeatureCodec;
import org.broad.tribble.Feature;

import javax.xml.bind.annotation.*;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * A feature source which derives its information
 * from a command line cli_plugin
 * User: jacob
 * Date: 2012/05/01
 */
@XmlAccessorType(XmlAccessType.NONE)
public abstract class PluginSource<E extends Feature, D extends Feature>{

    private static Logger log = Logger.getLogger(PluginSource.class);

    /**
     * Initial command tokens. This will typically include both
     * the executable and command, e.g. {"/usr/bin/bedtools", "intersect"}
     */
    @XmlList
    @XmlAttribute
    protected List<String> commands;

    @XmlJavaTypeAdapter(MyMapAdapter.class)
    protected LinkedHashMap<Argument, Object> arguments;

    @XmlElement
    protected PluginSpecReader.Parser parser;

    protected URL[] decodingLibURLs = new URL[0];

    @XmlAttribute
    protected String specPath = null;

    /**
     * For decoding, we may need to know how many columns
     * were written out in the first place
     */
    protected List<Map<String, Object>> attributes = new ArrayList<Map<String, Object>>(2);

    @SubtlyImportant
    protected PluginSource(){}

    public PluginSource(List<String> commands, LinkedHashMap<Argument, Object> arguments, PluginSpecReader.Parser parsingAttrs, String specPath) {
        this.commands = commands;
        this.arguments = arguments;
        this.parser = parsingAttrs;
        this.specPath = specPath;

        String[] libs = parsingAttrs.libs;
        libs = libs != null ? libs : new String[]{};
        try {
            decodingLibURLs = PluginSpecReader.getLibURLs(libs, FileUtils.getParent(specPath));
        } catch (MalformedURLException e) {
            log.error("Error parsing library URL", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Encode features into strings using {@link #getEncodingCodec(Argument)} and write them to the provided stream.
     * Stream will be closed after data written
     *
     * @param outputStream
     * @param features
     * @param argument
     * @return
     */
    protected final Map<String, Object> writeFeaturesToStream(OutputStream outputStream, Iterator<E> features, Argument argument)
            throws IOException {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream));

        Map<String, Object> attributes = null;
        if (features != null) {
            FeatureEncoder codec = getEncodingCodec(argument);

            attributes = codec.encodeAll(outputStream, features);

        }
        writer.flush();
        writer.close();

        return attributes;
    }

    protected final String[] genFullCommand(String chr, int start, int end, int zoom) throws IOException {

        List<String> fullCmd = new ArrayList<String>(commands);

        attributes.clear();
        Map<String, String[]> argValsById = new HashMap<String, String[]>(arguments.size());
        for (Map.Entry<Argument, Object> entry : arguments.entrySet()) {
            Argument arg = entry.getKey();

            assert arg.isValidValue(entry.getValue());
            String[] sVal = null;
            String ts = null;
            switch (arg.getType()) {
                case LONGTEXT:
                case TEXT:
                    ts = (String) entry.getValue();
                    if (ts == null || ts.trim().length() == 0) {
                        continue;
                    }
                    sVal = new String[]{ts};
                    if (arg.getType() == Argument.InputType.TEXT) {
                        sVal = ts.split("\\s+");
                    }
                    break;
                case FEATURE_TRACK:
                case DATA_TRACK:
                    ts = createTempFile((Track) entry.getValue(), arg, chr, start, end, zoom);
                    sVal = new String[]{ts};
                    break;
                case MULTI_FEATURE_TRACK:
                    sVal = createTempFiles((List<FeatureTrack>) entry.getValue(), arg, chr, start, end, zoom);
                    break;
            }

            if (arg.getId() != null) {
                argValsById.put(arg.getId(), sVal);
            }

            if (arg.isOutput()) {
                String cmdArg = arg.getCmdArg();
                if (cmdArg.trim().length() > 0) {
                    for (String argId : argValsById.keySet()) {
                        cmdArg = cmdArg.replace("$" + argId, argValsById.get(argId)[0]);
                    }

                    fullCmd.add(cmdArg);
                }
                fullCmd.addAll(Arrays.asList(sVal));
            }
        }

        return fullCmd.toArray(new String[0]);
    }

    /**
     * Convenience for calling {@link #createTempFile(org.broad.igv.track.Track, org.broad.igv.cli_plugin.Argument, String, int, int, int)};
     * on each track
     *
     * @param tracks
     * @param chr
     * @param start
     * @param end
     * @param zoom
     * @return
     * @throws java.io.IOException
     */
    private String[] createTempFiles(List<FeatureTrack> tracks, Argument argument, String chr, int start, int end, int zoom) throws IOException {
        String[] fileNames = new String[tracks.size()];
        int fi = 0;
        for (FeatureTrack track : tracks) {
            fileNames[fi++] = createTempFile(track, argument, chr, start, end, zoom);
        }
        return fileNames;
    }

    /**
     * Write out data from feature sources within the specified interval
     * to temporary files.
     *
     * @param track
     * @param chr
     * @param start
     * @param end
     * @return String with temp file name.
     * @throws java.io.IOException
     */
    protected abstract String createTempFile(Track track, Argument argument, String chr, int start, int end, int zoom) throws IOException;

    protected final String createTempFile(List<E> features, Argument argument) throws IOException {
        File outFile = File.createTempFile("features", ".tmp", null);
        outFile.deleteOnExit();

        Map<String, Object> attributes = writeFeaturesToStream(new FileOutputStream(outFile), features.iterator(), argument);
        String path = outFile.getAbsolutePath();
        this.attributes.add(attributes);
        return path;
    }

    /**
     * Perform the actual combination operation between the constituent data
     * sources. This implementation re-runs the operation each call.
     *
     * @param chr
     * @param start
     * @param end
     * @return
     * @throws java.io.IOException
     */
    protected final Iterator<D> getFeatures(String chr, int start, int end, int zoom) throws IOException {

        String[] fullCmd = genFullCommand(chr, start, end, zoom);

        //Start cli_plugin process
        Process pr = RuntimeUtils.startExternalProcess(fullCmd, null, null);

        //Read back in the data which cli_plugin output
        FeatureDecoder<D> codec = getDecodingCodec();
        return codec.decodeAll(pr.getInputStream(), parser.strict);
    }

    /**
     * Create ncoding codec, and apply inputs
     *
     * @param argument
     * @return
     */
    protected final FeatureEncoder<E> getEncodingCodec(Argument argument) {
        FeatureEncoder<E> codec = instantiateEncodingCodec(argument);
        codec.setInputs(Collections.unmodifiableList(commands), Collections.unmodifiableMap(arguments));
        return codec;
    }

    /**
     * Get the encoding codec for this argument. Default
     * is IGVBEDCodec, if there was none specified.
     * <p/>
     * Codec will be reflectively instantiated if it was specified
     * in the {@code argument}
     *
     * @param argument
     * @return
     */
    private final FeatureEncoder<E> instantiateEncodingCodec(Argument argument) {
        String encodingCodec = argument.getEncodingCodec();

        if (encodingCodec == null) return new AsciiEncoder(new IGVBEDCodec());

        try {
            URL[] libURLs = PluginSpecReader.getLibURLs(argument.getLibPaths(), FileUtils.getParent(specPath));
            if(libURLs == null) libURLs = new URL[0];
            ClassLoader loader = URLClassLoader.newInstance(
                    libURLs, getClass().getClassLoader()
            );
            Class clazz = loader.loadClass(encodingCodec);
            Constructor constructor = clazz.getConstructor();
            Object ocodec = constructor.newInstance();
            FeatureEncoder<E> codec;
            if (!(ocodec instanceof FeatureEncoder) && ocodec instanceof LineFeatureEncoder) {
                codec = new AsciiEncoder((LineFeatureEncoder<D>) ocodec);
            } else {
                codec = (FeatureEncoder<E>) ocodec;
            }
            return codec;

        } catch (ClassNotFoundException e) {
            log.error("Could not find class " + encodingCodec, e);
            throw new IllegalArgumentException(e);
        } catch (MalformedURLException e){
            log.error("Malformed library URL", e);
            throw new RuntimeException(e);
        } catch (Exception e) {
            log.error("Exception getting encoding codec", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Create decoding codec and set inputs and attributes
     *
     * @return
     */
    protected final FeatureDecoder<D> getDecodingCodec() {
        FeatureDecoder<D> codec = instantiateDecodingCodec(parser.decodingCodec, decodingLibURLs);
        codec.setInputs(Collections.unmodifiableList(commands), Collections.unmodifiableMap(arguments));
        codec.setAttributes(Collections.unmodifiableList(attributes));
        return codec;
    }

    /**
     * Instantiate decodingCodec, using {@code libURLs} as classpath. Will throw exceptions
     * if class cannot be instantiated
     *
     * @param decodingCodec
     * @param libURLs
     * @return
     */
    protected final FeatureDecoder<D> instantiateDecodingCodec(String decodingCodec, URL[] libURLs) {
        if (decodingCodec == null) {
            AsciiFeatureCodec<D> asciiCodec = CodecFactory.getCodec("." + parser.format, GenomeManager.getInstance().getCurrentGenome());
            if (asciiCodec == null) {
                throw new IllegalArgumentException("Unable to find codec for format " + parser.format);
            }
            return new AsciiDecoder.DecoderWrapper<D>(asciiCodec);
        }

        try {

            if(libURLs == null) libURLs = new URL[0];
            ClassLoader loader = URLClassLoader.newInstance(libURLs,
                    getClass().getClassLoader());

            Class clazz = loader.loadClass(decodingCodec);
            Constructor constructor = clazz.getConstructor();
            Object codec = constructor.newInstance();
            //User can pass in LineFeatureDecoder, we just wrap it
            if (!(codec instanceof FeatureDecoder) && codec instanceof LineFeatureDecoder) {
                return new AsciiDecoder((LineFeatureDecoder<D>) codec);
            }
            return (FeatureDecoder<D>) codec;

        } catch (ClassNotFoundException e) {
            log.error("Could not find class " + decodingCodec, e);
            throw new IllegalArgumentException(e);
        } catch (Exception e) {
            log.error("Exception getting decoding codec", e);
            throw new RuntimeException(e);
        }

    }


    public void getPersistentState() {
        /**
         * The structure here uses similar tags to the plugin XML spec, but is not exactly
         * the same. Because one and only one command was actually used we don't need
         * to nest as much. We also need to store argument values as well as inputs
         */

//        Map<String, String> parentProps = new HashMap<String, String>(5);

//        parentProps.put(DECODING_CODEC, parser.format);
//        //TODO Less ambiguous name to not clash with argument
//        parentProps.put(DECODING_LIBS, StringUtils.join(decodingLibURLs, ","));
//        parentProps.put(FORMAT, parser.format);
//        parentProps.put("specPath", specPath);
//
//        List<RecursiveAttributes> allChildren = new ArrayList<RecursiveAttributes>(4);
//
//
//        List<RecursiveAttributes> persCommands = new ArrayList<RecursiveAttributes>();
//        for(int ii=0; ii < commands.size(); ii++){
//            String command = commands.get(ii);
//            Map<String, String> props = new HashMap<String, String>(1);
//            props.put("value", command);
//            props.put("index", "" + ii);
//            RecursiveAttributes persCommand = new RecursiveAttributes(PluginSpecReader.CMD_ARG, props);
//            persCommands.add(persCommand);
//        }
//        RecursiveAttributes persCommandParent = new RecursiveAttributes(PluginSpecReader.COMMAND, Collections.<String, String>emptyMap(),
//                persCommands);
//
//
//        allChildren.add(persCommandParent);
//
//        for(Map.Entry<Object, Object> entry: arguments.entrySet()){
//            Argument argument = (Argument) entry.getKey();
//            List<String> values = null;
//            String sval;
//            switch (argument.getType()) {
//                case MULTI_FEATURE_TRACK:
//                    List<FeatureTrack> lVal = (List<FeatureTrack>) entry.getValue();
//                    values = new ArrayList<String>(lVal.size());
//                    for(FeatureTrack fTrack: lVal){
//                        values.add(fTrack.getId());
//                    }
//                    break;
//                case LONGTEXT:
//                case TEXT:
//                    sval = (String) entry.getValue();
//                    values = Arrays.asList(sval);
//                    break;
//                case FEATURE_TRACK:
//                case DATA_TRACK:
//                case ALIGNMENT_TRACK:
//                    sval = ((Track) entry.getValue()).getId();
//                    values = Arrays.asList(sval);
//                    break;
//            }
//
//            if(values == null) continue;
//
//            //Generate list of values for the argument
//            List<RecursiveAttributes> persValues = new ArrayList<RecursiveAttributes>();
//            for(String value: values){
//                Map<String, String> cprop = new HashMap<String, String>(1);
//                cprop.put(VALUE, value);
//                persValues.add(new RecursiveAttributes(VALUE, cprop));
//            }
//
//            //Group values together under relevant argument
//            //RecursiveAttributes persArgument = argument.getPersistentState();
//            //persArgument.getChildren().addAll(persValues);
//
//            //Arguments aren't grouped together, just put flat in the children of the highest level
//            //allChildren.add(persArgument);
//        }
//
//        RecursiveAttributes overall = new RecursiveAttributes(getClass().getName(), parentProps, allChildren);
//        return overall;
    }

    public void updateTrackReferences(List<Track> allTracks){
        MyMapAdapter.updateTrackReferences(arguments, allTracks);
    }

    static class XmlMap{
        public List<Argument> arg =
                new ArrayList<Argument>();
    }

    public final static class MyMapAdapter extends XmlAdapter<XmlMap, LinkedHashMap<Argument, Object>> {

        public static WeakReference<IGVSessionReader> sessionReader;

        public static void setSessionReader(IGVSessionReader igvSessionReader) {
            sessionReader = new WeakReference<IGVSessionReader>(igvSessionReader);
        }

        @Override
        public LinkedHashMap<Argument, Object> unmarshal(XmlMap v) throws Exception {
            LinkedHashMap<Argument, Object> argumentMap = new LinkedHashMap(v.arg.size());
            for(Argument argument: v.arg){
                Object oVal = null;
                switch (argument.getType()){
                    case LONGTEXT:
                    case TEXT:
                        oVal = argument.value.get(0);
                        break;
                    case MULTI_FEATURE_TRACK:
                    case FEATURE_TRACK:
                    case DATA_TRACK:
                    case ALIGNMENT_TRACK:
                        oVal = findTrackReference(argument, null);
                        break;
                }
                argumentMap.put(argument, oVal);
            }
            return argumentMap;
        }

        /**
         * Uses #sessionReader to lookup matching tracks by id, or
         * searches allTracks if sessionReader is null
         * @param trackId
         * @param allTracks
         * @return
         */
        private static Track getMatchingTrack(String trackId, List<Track> allTracks){
            IGVSessionReader reader = sessionReader.get();
            List<Track> matchingTracks;
            if(reader != null){
                matchingTracks = reader.getTracksById(trackId);
            }else{
                if(allTracks == null) throw new IllegalStateException("No session reader and no tracks to search to resolve Track references");
                matchingTracks = new ArrayList<Track>();
                for(Track track: allTracks){
                    if(trackId.equals(track.getId())){
                        matchingTracks.add(track);
                        break;
                    }
                }
            }
            if (matchingTracks == null || matchingTracks.size() == 0) {
                //Either the session file is corrupted, or we just haven't loaded the relevant track yet
                return null;
            }else if (matchingTracks.size() >= 2) {
                log.debug("Found multiple tracks with id  " + trackId + ", using the first");
            }
            return matchingTracks.get(0);
        }

        private static Object findTrackReference(Argument argument, List<Track> allTracks){
            Object oVal = null;
            switch (argument.getType()){
                case MULTI_FEATURE_TRACK:
                    List<FeatureTrack> inputTracks = new ArrayList<FeatureTrack>(argument.value.size());

                    for(String trackId: argument.value){
                        inputTracks.add((FeatureTrack) getMatchingTrack(trackId, allTracks));
                    }
                    oVal = inputTracks;
                    break;
                case FEATURE_TRACK:
                case DATA_TRACK:
                case ALIGNMENT_TRACK:
                    String trackId = argument.value.get(0);
                    oVal = getMatchingTrack(trackId, allTracks);
                    break;
            }
            return oVal;
        }

        public static void updateTrackReferences(Map<Argument, Object> argumentMap, List<Track> allTracks) {
            for(Argument argument: argumentMap.keySet()){
                //Reference already resolved
                if(argumentMap.get(argument) != null) continue;
                Object oVal = findTrackReference(argument, allTracks);
                argumentMap.put(argument, oVal);
            }
        }

        @Override
        public XmlMap marshal(LinkedHashMap<Argument, Object> v) throws Exception {
            XmlMap outMap = new XmlMap();
            for(Map.Entry<Argument, Object> loopEntry: v.entrySet()){
                Argument argument = loopEntry.getKey();
                List<String> values = null;
                String sval;
                switch (argument.getType()) {
                    case MULTI_FEATURE_TRACK:
                        List<FeatureTrack> lVal = (List<FeatureTrack>) loopEntry.getValue();
                        values = new ArrayList<String>(lVal.size());
                        for(FeatureTrack fTrack: lVal){
                            values.add(fTrack.getId());
                        }
                        break;
                    case LONGTEXT:
                    case TEXT:
                        sval = (String) loopEntry.getValue();
                        values = Arrays.asList(sval);
                        break;
                    case FEATURE_TRACK:
                    case DATA_TRACK:
                    case ALIGNMENT_TRACK:
                        sval = ((Track) loopEntry.getValue()).getId();
                        values = Arrays.asList(sval);
                        break;
                }

                argument.value = values;
                outMap.arg.add(argument);
            }
            return outMap;
        }

    }

}
