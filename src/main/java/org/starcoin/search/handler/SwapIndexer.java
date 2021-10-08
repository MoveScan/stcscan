package org.starcoin.search.handler;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

import java.util.Calendar;


public class SwapIndexer extends QuartzJobBean {

    @Autowired
    private SwapHandle swapHandle;
    private static final Logger logger = LoggerFactory.getLogger(SwapIndexer.class);

    @Override
    protected void executeInternal(JobExecutionContext jobExecutionContext)  {

        long endTs = getTimeStamp(0);
        long startTs = getTimeStamp(-1);
        swapHandle.swapStat(startTs, endTs);
        logger.info("swap index handle ok: {} , {}", startTs, endTs);
    }

    static long getTimeStamp(int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH) + day, 0, 0, 0);
        return calendar.getTimeInMillis();
    }

}
