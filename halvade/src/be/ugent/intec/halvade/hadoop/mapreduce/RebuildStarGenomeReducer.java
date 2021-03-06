/*
 * Copyright (C) 2014 ddecap
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package be.ugent.intec.halvade.hadoop.mapreduce;

import be.ugent.intec.halvade.hadoop.datatypes.GenomeSJ;
import be.ugent.intec.halvade.utils.HalvadeFileUtils;
import be.ugent.intec.halvade.utils.HalvadeConf;
import be.ugent.intec.halvade.utils.Logger;
import be.ugent.intec.halvade.tools.STARInstance;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

/**
 *
 * @author ddecap
 */
public class RebuildStarGenomeReducer extends Reducer<GenomeSJ, Text, LongWritable, Text> {
    protected String tmpDir;
    protected String mergeJS;
    protected BufferedWriter bw;
    protected int count;
    protected String bin, ref, out;
    protected String taskId;
    protected String jobId;
    protected int overhang = 100, threads;
    protected long mem;
    protected boolean requireUploadToHDFS = false, keep;
    protected int totalValCount;
    protected int totalKeyCount;
    protected ArrayList<Integer> keyFactors;

    @Override
    protected void reduce(GenomeSJ key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
        Iterator<Text> it = values.iterator();
        if(key.getType() == -1) {
            while(it.hasNext()) {
                bw.write(it.next().toString() + "\n");
                count++;
            }
            Logger.DEBUG("genomeSJ count: " + count);
        } else if (key.getType() == -2) {
            overhang = key.getSecKey();
            Logger.DEBUG("set overhang to " + overhang);
        } else {
            int valCount = 0;
            while(it.hasNext()) {
                valCount++;
                it.next();
            }
            keyFactors.add(valCount);
            totalValCount += valCount;
            totalKeyCount++;
            Logger.DEBUG("key: " + key + " count: " + valCount);
        }
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        Logger.DEBUG("total count: " + totalValCount);
        Logger.DEBUG("total keys: " + totalKeyCount);
        //for(Integer count : keyFactors) {
        //    int factor = Math.min(1, count / avg + 1); 
        //    Logger.DEBUG("count: " + count + " factor: " + factor + " new count: " + (count/factor));
        //}

        FileSystem fs = null;
        try {   
            fs = FileSystem.get(new URI(out), context.getConfiguration());  
        } catch (URISyntaxException ex) {
            Logger.EXCEPTION(ex);
        }
        
        bw.close();
        File mergeFile = new File(mergeJS);
        Logger.DEBUG("written " + count + " lines to " + mergeJS);
        HalvadeFileUtils.uploadFileToHDFS(fs, mergeFile.getAbsolutePath(), out + mergeFile.getName());

        // build new genome ref
        String pass2GenDir = HalvadeConf.getStarDirPass2HDFS(context.getConfiguration());
        String newGenomeDir = pass2GenDir;
        if(requireUploadToHDFS) {
            String pass2uid = HalvadeConf.getPass2UID(context.getConfiguration());
            newGenomeDir = tmpDir + pass2uid;
        }
        File starOut = new File(newGenomeDir);
        starOut.mkdirs();
        
        String stargtf = HalvadeConf.getStarGtf(context.getConfiguration());
        long time = STARInstance.rebuildStarGenome(context, bin, newGenomeDir, ref, mergeJS, 
                                                    overhang, threads, mem, stargtf);
        context.getCounter(HalvadeCounters.TIME_STAR_BUILD).increment(time);
        
        if(requireUploadToHDFS) {
            //upload to outputdir
            Logger.DEBUG("Uploading STAR genome to parallel filesystem...");
            fs.mkdirs(new Path(pass2GenDir));
            File[] genFiles = starOut.listFiles();
            for(File gen : genFiles) {
                HalvadeFileUtils.uploadFileToHDFS(fs, gen.getAbsolutePath(), pass2GenDir + gen.getName());
            }
//            HalvadeFileUtils.removeLocalDir(keep, newGenomeDir);
            Logger.DEBUG("Finished uploading new reference to " + pass2GenDir);
        }
        HalvadeFileUtils.removeLocalFile(mergeJS);
    }

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        totalValCount = 0;
        totalKeyCount = 0;
        keyFactors = new ArrayList<>();
        tmpDir = HalvadeConf.getScratchTempDir(context.getConfiguration());
//        refDir = HalvadeConf.getRefDirOnScratch(context.getConfiguration());
        keep = HalvadeConf.getKeepFiles(context.getConfiguration());
        requireUploadToHDFS = HalvadeConf.getReuploadStar(context.getConfiguration());
        out = HalvadeConf.getOutDir(context.getConfiguration()); 
        jobId = context.getJobID().toString();
        taskId = context.getTaskAttemptID().toString();
        taskId = taskId.substring(taskId.indexOf("r_"));
        mergeJS = tmpDir + taskId + "-SJ.out.tab";
        File file = new File(mergeJS);
        
        threads = HalvadeConf.getReducerThreads(context.getConfiguration());
        try {
            mem = Long.parseLong(context.getConfiguration().get("mapreduce.reduce.memory.mb"));
        } catch (NumberFormatException ex) {
            mem = 0;
        }
        bin = checkBinaries(context);
        try {
            ref = HalvadeFileUtils.downloadGATKIndex(context);
        } catch (URISyntaxException ex) {
            Logger.EXCEPTION(ex);
            throw new InterruptedException();
        }
        
        bw = new BufferedWriter(new FileWriter(file.getAbsoluteFile()));
        Logger.DEBUG("opened file write for " + mergeJS);
    }
    
    protected String checkBinaries(Reducer.Context context) throws IOException {
        Logger.DEBUG("Checking for binaries...");
        String binDir = null;
        URI[] localPaths = context.getCacheArchives();
        for(int i = 0; i < localPaths.length; i++ ) {
            Path path = new Path(localPaths[i].getPath());
            if(path.getName().startsWith("bin") && path.getName().endsWith(".tar.gz")) {
                binDir = "./" + path.getName() + "/bin/";
            }
        }
        if(binDir == null) 
            throw new IOException("Can't find the binary file, the filename should start with 'bin' and end in '.tar.gz'");
        printDirectoryTree(new File(binDir), 0);
        return binDir;
    }
    
    protected void printDirectoryTree(File dir, int level) {
        String whitespace = "";
        for(int i = 0; i < level; i++)
            whitespace += "\t";
        File[] list = dir.listFiles();
        if(list != null) {
            for(int i = 0; i < list.length; i++ ) {
                java.nio.file.Path path = FileSystems.getDefault().getPath(list[i].getAbsolutePath());
                String attr = "";
                if(list[i].isDirectory()) 
                    attr += "D ";
                else 
                    attr += "F ";
                if(list[i].canExecute()) 
                    attr += "E ";
                else 
                    attr += "NE ";
                if(list[i].canRead()) 
                    attr += "R ";
                else 
                    attr += "NR ";
                if(list[i].canWrite()) 
                    attr += "W ";
                else 
                    attr += "NW ";
                if(Files.isSymbolicLink(path)) 
                    attr += "S ";
                else 
                    attr += "NS ";
                    
                Logger.DEBUG(whitespace + attr + "\t" + list[i].getName());
                if(list[i].isDirectory())
                    printDirectoryTree(list[i], level + 1);
            }
        } else {
                    Logger.DEBUG(whitespace + "N");
        }
    }
    
    
}
