package com.echomind;

import com.echomind.saas.architecture.LegacySaaSBridge;
import com.echomind.saas.platform.outbox.OutboxWorkerMain;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@LegacySaaSBridge("packaged launcher delegates explicit worker mode to the standalone SaaS Outbox process")
public class EchoMindJavaApplication {

    public static void main(String[] args) {
        if (args.length == 1 && "--outbox-worker".equals(args[0])) {
            OutboxWorkerMain.run();
            return;
        }
        SpringApplication.run(EchoMindJavaApplication.class, args);
    }
}
