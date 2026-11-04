package com.lab.sipim;

import com.lab.sipim.security.LoginTokens;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LoginTokenConcurrencyTest {
    @Test void revocationRemainsFinalWhileReadersRenewSessions() throws Exception {
        LoginTokens tokens=new LoginTokens();
        ExecutorService readers=Executors.newFixedThreadPool(4);
        try {
            for(int round=0;round<100;round++) {
                String token=tokens.issue(1L), other=tokens.issue(2L);
                CountDownLatch start=new CountDownLatch(1);
                var tasks=new java.util.ArrayList<Future<?>>();
                for(int n=0;n<4;n++) tasks.add(readers.submit(() -> {
                    start.await();
                    for(int i=0;i<200;i++) tokens.userIdOf(token);
                    return null;
                }));
                start.countDown(); tokens.revokeUser(1L);
                for(var task:tasks) task.get(5,TimeUnit.SECONDS);
                assertThat(tokens.userIdOf(token)).isEmpty();
                assertThat(tokens.userIdOf(other)).contains(2L);
                tokens.revoke(other);
            }
        } finally {readers.shutdownNow();}
    }
}
