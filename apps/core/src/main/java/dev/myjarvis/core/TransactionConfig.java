package dev.myjarvis.core;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Configuration
class TransactionConfig {
 @Bean TransactionTemplate transactions(PlatformTransactionManager manager){return new TransactionTemplate(manager);}
}
