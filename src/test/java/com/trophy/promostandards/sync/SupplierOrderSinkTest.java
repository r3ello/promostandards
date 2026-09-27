package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.SupplierOrderService.Line;
import com.trophy.promostandards.sync.SupplierOrderService.Preview;
import com.trophy.promostandards.sync.SupplierOrderService.ShipTo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Sends order #1049 — its real fields and the customizer's {@code _trophy_items} — through the real
 * mailer to a local SMTP sink, so the message can be read exactly as PaceSetter would get it,
 * attachment included. Off unless asked for:
 *
 * <pre>
 * python D:/ClaudeCode/toolbox/scripts/mail/smtp_sink.py --port 1025 --out-dir ./smtp-sink
 * mvn -o test -Dtest=SupplierOrderSinkTest -Dsmtp.sink.port=1025
 * </pre>
 */
@EnabledIfSystemProperty(named = "smtp.sink.port", matches = "\\d+")
class SupplierOrderSinkTest {

    @Test
    void sendsOrder1049ToTheSink() {
        JavaMailSenderImpl smtp = new JavaMailSenderImpl();
        smtp.setHost("localhost");
        smtp.setPort(Integer.parseInt(System.getProperty("smtp.sink.port")));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("mailSender", smtp);
        SupplierOrderMailer mailer = new SupplierOrderMailer(beans.getBeanProvider(JavaMailSender.class));
        SupplierOrderProperties props = new SupplierOrderProperties(true, "pacesetter-orders@example.invalid",
                "shop@example.invalid", null, "orders@example.invalid", null, null, null, "TP-DEV",
                "TrophyPartner (dev)", null, "UPS #4E4W93");

        Preview order = new Preview("gid://shopify/Order/7300090560606", "#1049", "1049", "2026-09-27T09:34:38Z",
                true, "PAID", "UNFULFILLED", null, null,
                new ShipTo("rafael bello", null, "panay", null, "Wake Forest", "North Carolina", "NC", "27587",
                        "United States", "US", null),
                "Standard", List.of(new Line("GM828", "Red/Black Spiral Teardrop Art Glass", null, "PS9349", 3, 3,
                        new BigDecimal("51.00"), "USD", Map.of(), TrophyItem.parse(TrophyItemTest.ORDER_1049))),
                List.of(), List.of(), List.of(), null);

        mailer.send(new SupplierOrderEmail(props, new DefaultResourceLoader(), mailer).render(order));
    }
}
