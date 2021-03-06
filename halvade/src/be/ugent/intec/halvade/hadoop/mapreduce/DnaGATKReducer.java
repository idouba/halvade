/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package be.ugent.intec.halvade.hadoop.mapreduce;

import be.ugent.intec.halvade.tools.GATKTools;
import be.ugent.intec.halvade.tools.PreprocessingTools;
import be.ugent.intec.halvade.tools.QualityException;
import be.ugent.intec.halvade.utils.ChromosomeRange;
import be.ugent.intec.halvade.utils.Logger;
import be.ugent.intec.halvade.utils.HalvadeConf;
import be.ugent.intec.halvade.utils.HalvadeFileUtils;
import be.ugent.intec.halvade.utils.SAMRecordIterator;
import org.seqdoop.hadoop_bam.SAMRecordWritable;
import java.io.IOException;
import java.net.URISyntaxException;

/**
 *
 * @author ddecap
 */
public class DnaGATKReducer extends GATKReducer {

    @Override
    protected void processAlignments(Iterable<SAMRecordWritable> values, Context context, PreprocessingTools tools, GATKTools gatk) throws IOException, InterruptedException, URISyntaxException, QualityException {
        long startTime = System.currentTimeMillis();
        // temporary files
        String region = tmpFileBase + "-region.intervals";
        String preprocess = tmpFileBase + ".bam";
        String tmpFile1 = tmpFileBase + "-2.bam";
        String tmpFile2 = tmpFileBase + "-3.bam";
        String snps = tmpFileBase + (outputGVCF ? ".g.vcf" : ".vcf");    
        boolean useElPrep = HalvadeConf.getUseElPrep(context.getConfiguration());
        boolean splitNtrim = HalvadeConf.getSplitNTrim(context.getConfiguration());
        ChromosomeRange r = new ChromosomeRange();
        SAMRecordIterator SAMit = new SAMRecordIterator(values.iterator(), header, r, fixQualEnc);
        
        if(useElPrep && isFirstAttempt) 
            elPrepPreprocess(context, tools, SAMit, preprocess);
        else  {
            if(!isFirstAttempt) Logger.DEBUG("attempt " + taskId + ", preprocessing with Picard for smaller peak memory");
            if(redistribute) {
                // assume this is after all other tasks and increase threads to 6!
                threads = 6;
                gatk.setThreads(threads);
                Logger.DEBUG("redistributing... using 6 cores");
            }
            PicardPreprocess(context, tools, SAMit, preprocess);
        }
        region = makeRegionFile(context, r, tools, region);
        if(region == null) return;
        
        
        String indelRealnInput = preprocess;
        // do splitntrim if option
        if(splitNtrim) {
            String tmpFile0 = tmpFileBase + "-1.bam";
            splitNTrim(context, region, gatk, preprocess, tmpFile0, false);
            indelRealnInput = tmpFile0;
        }    
        String varCalInput = indelRealnInput;
        if (doIndelRealignment) {
            indelRealignment(context, region, gatk, indelRealnInput, tmpFile1);  
            varCalInput = tmpFile1;
        }
        
        if(skipBQSR) {
            DnaVariantCalling(context, region, gatk, varCalInput, snps);  
        } else {            
            baseQualityScoreRecalibration(context, region, r, tools, gatk, varCalInput, tmpFile2);        
            DnaVariantCalling(context, region, gatk, tmpFile2, snps);
        }
        variantFiles.add(snps);
           
        HalvadeFileUtils.removeLocalFile(region);
        long estimatedTime = System.currentTimeMillis() - startTime;
        Logger.DEBUG("total estimated time: " + estimatedTime / 1000);
    }
    
}
