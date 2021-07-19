package org.starcoin.search.handler;

import com.thetransactioncompany.jsonrpc2.client.JSONRPC2SessionException;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.starcoin.api.BlockRPCClient;
import org.starcoin.api.TransactionRPCClient;
import org.starcoin.bean.Block;
import org.starcoin.bean.BlockHeader;
import org.starcoin.bean.BlockMetadata;
import org.starcoin.bean.Transaction;
import org.starcoin.search.bean.Offset;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;


public class IndexerHandle extends QuartzJobBean {
    private final static int HANDLE_COUNT = 100;
    private static Logger logger = LoggerFactory.getLogger(IndexerHandle.class);

    private Offset localOffset;
    private BlockHeader currentHandleHeader;

    @Value("${starcoin.network}")
    private String network;

    @Value("${starcoin.indexer.bulk_size}")
    private long bulkSize;

    @Autowired
    private ElasticSearchHandler elasticSearchHandler;

    @Autowired
    private TransactionRPCClient transactionRPCClient;

    @Autowired
    private BlockRPCClient blockRPCClient;

    @PostConstruct
    public void initOffset() {
        localOffset = elasticSearchHandler.getRemoteOffset();
        //update current handle header
        try {
            if (localOffset != null) {
                currentHandleHeader = blockRPCClient.getBlockByHeight(localOffset.getBlockHeight()).getHeader();
            }else {
                logger.warn("offset is null,init reset to genesis");
                currentHandleHeader = blockRPCClient.getBlockByHeight(0).getHeader();
                localOffset = new Offset(0, currentHandleHeader.getBlockHash());
                elasticSearchHandler.setRemoteOffset(localOffset);
                logger.info("init offset ok: {}", localOffset);
            }

        } catch (JSONRPC2SessionException e) {
            logger.error("set current header error:", e);
        }
    }

    @Override
    protected void executeInternal(JobExecutionContext jobExecutionContext) {
        //read current offset
        if (localOffset == null || currentHandleHeader == null) {
            logger.warn("local offset error, reset it.");
            initOffset();
        }
        Offset remoteOffset = elasticSearchHandler.getRemoteOffset();
        logger.info("current remote offset: {}", remoteOffset);
        if (remoteOffset == null) {
            logger.warn("offset must not null!!");
            return;
        }
        if (remoteOffset.getBlockHeight() > localOffset.getBlockHeight()) {
            logger.info("indexer equalize chain blocks.");
            return;
        }
        //read head
        try {
            BlockHeader chainHeader = blockRPCClient.getChainHeader();
            //calculate bulk size
            long headHeight = chainHeader.getHeight();
            long bulkNumber = Math.min(headHeight - localOffset.getBlockHeight(), bulkSize);
            int index = 1;
            List<Block> blockList = new ArrayList<>();
            while (index <= bulkNumber) {
                long readNumber = localOffset.getBlockHeight() + index;
                Block block = blockRPCClient.getBlockByHeight(readNumber);
                BlockMetadata metadata = null;
                if (!block.getHeader().getParentHash().equals(currentHandleHeader.getBlockHash())) {
                    //fork occurs
                    logger.warn("Fork detected, roll back: {}, {}, {}", readNumber, block.getHeader().getParentHash(), currentHandleHeader.getBlockHash());
                    //todo roll back handle
                }
                //set event
                List<Transaction> transactionList = transactionRPCClient.getBlockTransactions(block.getHeader().getBlockHash());
                for (Transaction transaction : transactionList) {
                    Transaction userTransaction = transactionRPCClient.getTransactionByHash(transaction.getTransactionHash());
                    if (userTransaction != null) {
                        transaction.setUserTransaction(userTransaction.getUserTransaction());
                        metadata = userTransaction.getBlockMetadata();
                    }
                    transaction.setEvents(transactionRPCClient.getTransactionEvents(transaction.getTransactionHash()));
                }
                block.setTransactionList(transactionList);
                block.setBlockMetadata(metadata);
                blockList.add(block);
                //update current header
                currentHandleHeader = block.getHeader();
                index++;
                logger.debug("add block: {}", block.getHeader());
            }
            //bulk execute
            elasticSearchHandler.bulk(blockList);
            //update offset
            localOffset.setBlockHeight(currentHandleHeader.getHeight());
            localOffset.setBlockHash(currentHandleHeader.getBlockHash());
            elasticSearchHandler.setRemoteOffset(localOffset);
        } catch (JSONRPC2SessionException e) {
            logger.error("chain header error:", e);
        }

//        try {
//            PendingTransaction txn = transactionRPCClient.getTransaction("0x19ee1bb9bc881cb7e88be257e792107674cf2001b24e8e0c1e015ed3779686e9");
//            System.out.println(txn);
//        } catch (JSONRPC2SessionException e) {
//            e.printStackTrace();
//        }
//        try {
////            Block block = blockRPCClient.getBlockByHash("0x002600028fd0d0bd9163366ec265595f61979907eb4532952321afe8a41f1859");
////            List<Block> blocks = blockRPCClient.getBlockListFromHeight(982348, 10);
////            for (Block block1: blocks) {
////                logger.info(String.valueOf(block1));
////            }
//            BlockHeader head = blockRPCClient.getChainHeader();
//            logger.info(head.toString());
//            List<Transaction> transactions = transactionRPCClient.getBlockTransactions("0xce2c6b3813239af2a6ed60d4877296fcca0d93dc192dc9fceede4e31486c77e8");
//            for( Transaction transaction: transactions) {
//                logger.info(transaction.toString());
//                Transaction txn = transactionRPCClient.getTransactionByHash(transaction.getTransactionHash());
//                logger.info(txn.toString());
//                List<Event> events = transactionRPCClient.getTransactionEvents(transaction.getTransactionHash());
//                for(Event event: events) {
//                    logger.info(event.toString());
//                }
//            }
//        } catch (JSONRPC2SessionException e) {
//            e.printStackTrace();
//        }
        logger.info("runing...");
    }

}