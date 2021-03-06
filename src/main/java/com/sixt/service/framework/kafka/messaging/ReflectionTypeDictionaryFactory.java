/**
 * Copyright 2016-2017 Sixt GmbH & Co. Autovermietung KG
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.sixt.service.framework.kafka.messaging;

import com.google.inject.ConfigurationException;
import com.google.inject.Injector;
import com.google.inject.ProvisionException;
import com.google.protobuf.Parser;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.NotNull;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A TypeDictionary that finds MessageHandlers as well as Protobuf messages via reflection.
 * <p>
 * The scope of this TypeDictionary is global: all found messages and handlers from the classpath are put to this dictionary.
 * <p>
 * If you want to distinguish e.g. between handlers for the same message type but different topics, this is not the
 * right TypeDictionary implementation.
 */
public final class ReflectionTypeDictionaryFactory {
    private static final Logger logger = LoggerFactory.getLogger(ReflectionTypeDictionaryFactory.class);

    private final Injector injector;

    public ReflectionTypeDictionaryFactory(@NotNull Injector injector) {
        this.injector = injector;
    }

    public TypeDictionary createFromClasspath() {
        logger.info("Creating TypeDictionary using reflection from standard classpath.");
        return new TypeDictionary(populateHandlersFromClasspath(), populateParsersFromClasspath());
    }

    public Map<MessageType, MessageHandler<? extends com.google.protobuf.Message>> populateHandlersFromClasspath() {
        Map<MessageType, MessageHandler<? extends com.google.protobuf.Message>> handlers = new HashMap<>();

        List<Class<? extends MessageHandler>> foundHandlers = new ArrayList<>();

        new FastClasspathScanner()
                .matchClassesImplementing(MessageHandler.class, matchingClass ->
                        foundHandlers.add(matchingClass)).scan();

        foundHandlers.forEach((handlerClass) -> {
            Type[] interfaces = handlerClass.getGenericInterfaces();

            for (Type it : interfaces) {
                if (it instanceof ParameterizedType) {

                    ParameterizedType pt = ((ParameterizedType) it);

                    if (pt.getRawType().getTypeName().equals((MessageHandler.class.getTypeName()))) {
                        // We expect exactly one type argument
                        Type t = pt.getActualTypeArguments()[0];

                        MessageType type = MessageType.of(t);
                        MessageHandler<? extends com.google.protobuf.Message> handler = null;

                        try {
                            // Ask Guice for an instance of the handler.
                            // We cannot simply use e.g. the default constructor as any meaningful handler would need to
                            // be wired to dependencies such as databases, metrics, etc.
                            handler = (MessageHandler<? extends com.google.protobuf.Message>) injector.getInstance(handlerClass);
                        } catch (ConfigurationException | ProvisionException e) {
                            logger.warn("Cannot instantiate MessageHandler {} using Guice.", handlerClass, e);
                        }

                        if (handler != null) {
                            MessageHandler previous = handlers.put(type, handler);
                            if (previous == null) {
                                logger.info("Added message handler {} for type {}", handlerClass, type);
                            } else {
                                logger.warn("Duplicate message handler {} for type {} was replaced by {}", previous.getClass().getTypeName(), type, handlerClass);
                            }
                        }
                    }
                } else {
                    logger.warn("Cannot add untyped instance of MessageHander {} to TypeDictionary", handlerClass.getTypeName());
                }
            }
        });

        return handlers;
    }

    public Map<MessageType, Parser<com.google.protobuf.Message>> populateParsersFromClasspath() {
        Map<MessageType, Parser<com.google.protobuf.Message>> parsers = new HashMap<>();
        List<Class<? extends com.google.protobuf.GeneratedMessageV3>> foundProtoMessages = new ArrayList<>();

        new FastClasspathScanner()
                .matchSubclassesOf(com.google.protobuf.GeneratedMessageV3.class, matchingClass ->
                        foundProtoMessages.add(matchingClass)).scan();

        // This algorithm adds parsers for all protobuf messages in the classpath including base types such as com.google.protobuf.DoubleValue.
        for (Class<? extends com.google.protobuf.GeneratedMessageV3> clazz : foundProtoMessages) {
            try {
                java.lang.reflect.Method method = clazz.getMethod("parser"); // static method, no arguments
                @SuppressWarnings("unchecked")
                Parser<com.google.protobuf.Message> parser = (Parser<com.google.protobuf.Message>) method.invoke(null, (Object[]) null); // static method, no arguments
                parsers.put(MessageType.of(clazz), parser);

                // too noisy: logger.debug("Added parser for protobuf type {}", clazz.getTypeName());

            } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException ignored) {
                // too noisy: logger.debug("Ignoring protobuf type {} as we cannot invoke static method parse().", clazz.getTypeName());
            }
        }

        return parsers;
    }


}
