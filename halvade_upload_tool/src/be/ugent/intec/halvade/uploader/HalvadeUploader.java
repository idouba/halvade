/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package be.ugent.intec.halvade.uploader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.commons.cli.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 *
 * @author ddecap
 */
public class HalvadeUploader  extends Configured implements Tool {
    protected Options options = new Options();
    private int mthreads = 1;
    private String manifest;
    private String outputDir;
    private String credFile;
    private int bestFileSize = 60000000; // <64MB
    
    
    private String accessKey;
    private String secretKey;
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {
        // TODO code application logic here
        Configuration c = new Configuration();
        HalvadeUploader hau = new HalvadeUploader();
        int res = ToolRunner.run(c, hau, args);
    }
    
    @Override
    public int run(String[] strings) throws Exception {
        try {
            parseArguments(strings);  
            processFiles();
        } catch (ParseException e) {
            // automatically generate the help statement
            System.err.println("Error parsing: " + e.getMessage());
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp( "java -jar HalvadeAWSUploader -M <MANIFEST> -O <OUT> -B <BUCKET> [options]", options );
        }
        return 0;
    }
    
    private int processFiles() throws IOException, InterruptedException, URISyntaxException {    
        Timer timer = new Timer();
        timer.start();
        
        AWSUploader upl = null;
        FileSystem fs = null;
        // write to s3?
        boolean useAWS = false;
        if(outputDir.startsWith("s3")) {
            useAWS = true;
            readCredentials();
            String existingBucketName = outputDir; // TODO: fix this
            upl = new AWSUploader(existingBucketName, accessKey, secretKey);
        } else {
            Configuration conf = getConf();
            fs = FileSystem.get(new URI(outputDir), conf);
            Path outpath = new Path(outputDir);
            if (fs.exists(outpath) && !fs.getFileStatus(outpath).isDirectory()) {
                Logger.DEBUG("please provide an output directory");
                return 1;
            }
        }
        
        FastQFileReader pairedReader = FastQFileReader.getPairedInstance();
        FastQFileReader singleReader = FastQFileReader.getSingleInstance();
        if(manifest != null) {
            Logger.DEBUG("reading input files from " + manifest);
            // read from file
            BufferedReader br = new BufferedReader(new FileReader(manifest)); 
            String line;
            while ((line = br.readLine()) != null) {
                String[] files = line.split("\t");
                if(files.length == 2) {
                    pairedReader.addFilePair(files[0], files[1]);
                    File f = new File(files[0]);
                    f = new File(files[1]);
                } else if(files.length == 1) {
                    singleReader.addSingleFile(files[0]);
                    File f = new File(files[0]);
                }
            }
        }
        
        int bestThreads = mthreads;
        long maxFileSize = getBestFileSize(); 
        if(useAWS) {
            AWSInterleaveFiles[] fileThreads = new AWSInterleaveFiles[bestThreads];
            // start interleaveFile threads
            for(int t = 0; t < bestThreads; t++) {
                fileThreads[t] = new AWSInterleaveFiles(
                        outputDir + "pthread" + t + "_",  
                        outputDir + "sthread" + t + "_", 
                        maxFileSize, 
                        upl);
                fileThreads[t].start();
            }
            for(int t = 0; t < bestThreads; t++)
                fileThreads[t].join();
            if(upl != null)
                upl.shutDownNow(); 
        } else {
            
            HDFSInterleaveFiles[] fileThreads = new HDFSInterleaveFiles[bestThreads];
            // start interleaveFile threads
            for(int t = 0; t < bestThreads; t++) {
                fileThreads[t] = new HDFSInterleaveFiles(
                        outputDir + "pthread" + t + "_",  
                        outputDir + "sthread" + t + "_", 
                        maxFileSize, 
                        fs);
                fileThreads[t].start();
            }
            for(int t = 0; t < bestThreads; t++)
                fileThreads[t].join();
        }
        timer.stop();
        Logger.DEBUG("Time to process data: " + timer.getFormattedCurrentTime());     
        return 0;
    }
    
    private long getBestFileSize() {
        return bestFileSize;
    }
    
    private void readCredentials() throws FileNotFoundException, IOException {        
        ObjectMapper jsonMapper = new ObjectMapper();
        JsonNode root = jsonMapper.readTree(new File(credFile));
        accessKey = root.get("access_id").asText();
        secretKey = root.get("private_key").asText();    
    }
    
    public void createOptions() {
        Option optOut = OptionBuilder.withArgName( "output" )
                                .hasArg()
                                .isRequired(true)
                                .withDescription(  "Output directory on s3 [s3://bucketname/folder/] or HDFS [/dir/on/hdfs/]." )
                                .create( "O" );
        Option optCred = OptionBuilder.withArgName( "credentials" )
                                .hasArg()
                                .withDescription(  "Give the credential file to access AWS." )
                                .create( "cred" );
        Option optMan = OptionBuilder.withArgName( "manifest" )
                                .hasArg()
                                .isRequired(true)
                                .withDescription(  "Filename containing the input files to be put on S3/HDFS." )
                                .create( "M" );
        Option optSize = OptionBuilder.withArgName( "size" )
                                .hasArg()
                                .withDescription(  "Sets the maximum filesize of each split in MB." )
                                .create( "s" );
        Option optThreads = OptionBuilder.withArgName( "threads" )
                                .hasArg()
                                .withDescription(  "Sets the available threads [1]." )
                                .create( "t" );
        
        options.addOption(optOut);
        options.addOption(optMan);
        options.addOption(optThreads);
        options.addOption(optCred);
        options.addOption(optSize);
    }
    
    public void parseArguments(String[] args) throws ParseException {
        createOptions();
        CommandLineParser parser = new GnuParser();
        CommandLine line = parser.parse(options, args);
        manifest = line.getOptionValue("M");
        outputDir = line.getOptionValue("O");
        if(!outputDir.endsWith("/")) outputDir += "/";
        
        if (line.hasOption("cred"))
            credFile = line.getOptionValue("cred");        
        if(line.hasOption("t"))
            mthreads = Integer.parseInt(line.getOptionValue("t"));
        if(line.hasOption("s"))
            bestFileSize = Integer.parseInt(line.getOptionValue("s"));
    }

}