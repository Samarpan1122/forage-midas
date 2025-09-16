package com.jpmc.midascore.component;

import com.jpmc.midascore.entity.TransactionRecord;
import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.foundation.Incentive;
import com.jpmc.midascore.foundation.Transaction;
import com.jpmc.midascore.repository.TransactionRepository;
import com.jpmc.midascore.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Component
public class DatabaseConduit {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConduit.class);
    private static final String INCENTIVE_API_URL = "http://localhost:8080/incentive";
    
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final RestTemplate restTemplate;

    public DatabaseConduit(UserRepository userRepository, TransactionRepository transactionRepository, RestTemplate restTemplate) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.restTemplate = restTemplate;
    }

    public void save(UserRecord userRecord) {
        userRepository.save(userRecord);
    }
    
    public float getUserBalance(Long userId) {
        UserRecord user = userRepository.findById(userId.longValue());
        return user != null ? user.getBalance() : 0.0f;
    }

    @Transactional
    public boolean processTransaction(Transaction transaction) {
        logger.info("Processing transaction: {}", transaction);
        
        // Validate sender and recipient exist
        UserRecord sender = userRepository.findById(transaction.getSenderId());
        UserRecord recipient = userRepository.findById(transaction.getRecipientId());
        
        if (sender == null) {
            logger.warn("Invalid senderId: {}", transaction.getSenderId());
            return false;
        }
        
        if (recipient == null) {
            logger.warn("Invalid recipientId: {}", transaction.getRecipientId());
            return false;
        }
        
        // Validate sender has sufficient balance
        if (sender.getBalance() < transaction.getAmount()) {
            logger.warn("Insufficient balance. Sender {} has {}, transaction amount: {}", 
                       sender.getName(), sender.getBalance(), transaction.getAmount());
            return false;
        }
        
        // Call incentive API to get incentive amount
        float incentiveAmount = 0.0f;
        try {
            logger.info("Calling incentive API for transaction: {}", transaction);
            Incentive incentive = restTemplate.postForObject(INCENTIVE_API_URL, transaction, Incentive.class);
            if (incentive != null) {
                incentiveAmount = incentive.getAmount();
                logger.info("Received incentive amount: {}", incentiveAmount);
            } else {
                logger.warn("Incentive API returned null response");
            }
        } catch (Exception e) {
            logger.error("Failed to call incentive API: {}", e.getMessage());
            // Continue processing without incentive if API call fails
        }
        
        // Process the transaction
        sender.setBalance(sender.getBalance() - transaction.getAmount());
        recipient.setBalance(recipient.getBalance() + transaction.getAmount() + incentiveAmount);
        
        // Save updated balances
        userRepository.save(sender);
        userRepository.save(recipient);
        
        // Record the transaction
        TransactionRecord transactionRecord = new TransactionRecord(sender, recipient, transaction.getAmount(), incentiveAmount);
        transactionRepository.save(transactionRecord);
        
        logger.info("Transaction processed successfully. Sender {} new balance: {}, Recipient {} new balance: {}, Incentive: {}",
                   sender.getName(), sender.getBalance(), recipient.getName(), recipient.getBalance(), incentiveAmount);
        
        return true;
    }

}
