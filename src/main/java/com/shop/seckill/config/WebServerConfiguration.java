package com.shop.seckill.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

//定制化内嵌Tomcat开发
//
//当Spring容器内没有TomcatEmbeddedServletContainerFactory这个bean时，会把此bean加载进来
@Configuration
public class WebServerConfiguration implements WebServerFactoryCustomizer {
    @Override
    public void customize(WebServerFactory factory) {
        //使用对应工厂类提供给我们的接口，定制化Tomcat connector
        ((TomcatServletWebServerFactory)factory).addConnectorCustomizers(new TomcatConnectorCustomizer() {
            @Override
            public void customize(Connector connector) {
                Http11NioProtocol protocol = (Http11NioProtocol)connector.getProtocolHandler();
                //定制化KeepAlive Timeout为30秒
                //KeepAliveTimeout:多少毫秒后不响应的断开keepalive
                protocol.setKeepAliveTimeout(30000);
                //10000个请求则自动断开
                //MaxKeepAliveRequests:多少次请求后keepalive断开失效
                protocol.setMaxKeepAliveRequests(10000);
            }
        });
    }
}
