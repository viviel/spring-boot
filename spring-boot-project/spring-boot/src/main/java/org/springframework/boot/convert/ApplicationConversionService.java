/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.convert;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalConverter;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterRegistry;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.core.convert.converter.GenericConverter.ConvertiblePair;
import org.springframework.core.convert.support.ConfigurableConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.format.Formatter;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.Parser;
import org.springframework.format.Printer;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.util.StringValueResolver;

/**
 * A specialization of {@link FormattingConversionService} configured by default with
 * converters and formatters appropriate for most Spring Boot applications.
 * <p>
 * Designed for direct instantiation but also exposes the static
 * {@link #addApplicationConverters} and
 * {@link #addApplicationFormatters(FormatterRegistry)} utility methods for ad-hoc use
 * against registry instance.
 *
 * @author Phillip Webb
 * @author 郭 世雄
 * @since 2.0.0
 */
public class ApplicationConversionService extends FormattingConversionService {

	private static volatile ApplicationConversionService sharedInstance;

	public ApplicationConversionService() {
		this(null);
	}

	public ApplicationConversionService(StringValueResolver embeddedValueResolver) {
		if (embeddedValueResolver != null) {
			setEmbeddedValueResolver(embeddedValueResolver);
		}
		configure(this);
	}

	/**
	 * Return {@code true} if objects of {@code sourceType} can be converted to the
	 * {@code targetType} and the converter has {@code Object.class} as a supported source
	 * type.
	 * @param sourceType the source type to test
	 * @param targetType the target type to test
	 * @return if conversion happens via an {@code ObjectTo...} converter
	 * @since 2.4.3
	 */
	public boolean isConvertViaObjectSourceType(TypeDescriptor sourceType, TypeDescriptor targetType) {
		GenericConverter converter = getConverter(sourceType, targetType);
		Set<ConvertiblePair> pairs = (converter != null) ? converter.getConvertibleTypes() : null;
		if (pairs != null) {
			for (ConvertiblePair pair : pairs) {
				if (Object.class.equals(pair.getSourceType())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Return a shared default application {@code ConversionService} instance, lazily
	 * building it once needed.
	 * <p>
	 * Note: This method actually returns an {@link ApplicationConversionService}
	 * instance. However, the {@code ConversionService} signature has been preserved for
	 * binary compatibility.
	 * @return the shared {@code ApplicationConversionService} instance (never
	 * {@code null})
	 */
	public static ConversionService getSharedInstance() {
		ApplicationConversionService sharedInstance = ApplicationConversionService.sharedInstance;
		if (sharedInstance == null) {
			synchronized (ApplicationConversionService.class) {
				sharedInstance = ApplicationConversionService.sharedInstance;
				if (sharedInstance == null) {
					sharedInstance = new ApplicationConversionService();
					ApplicationConversionService.sharedInstance = sharedInstance;
				}
			}
		}
		return sharedInstance;
	}

	/**
	 * Configure the given {@link FormatterRegistry} with formatters and converters
	 * appropriate for most Spring Boot applications.
	 * @param registry the registry of converters to add to (must also be castable to
	 * ConversionService, e.g. being a {@link ConfigurableConversionService})
	 * @throws ClassCastException if the given FormatterRegistry could not be cast to a
	 * ConversionService
	 */
	public static void configure(FormatterRegistry registry) {
		DefaultConversionService.addDefaultConverters(registry);
		DefaultFormattingConversionService.addDefaultFormatters(registry);
		addApplicationFormatters(registry);
		addApplicationConverters(registry);
	}

	/**
	 * Add converters useful for most Spring Boot applications.
	 * @param registry the registry of converters to add to (must also be castable to
	 * ConversionService, e.g. being a {@link ConfigurableConversionService})
	 * @throws ClassCastException if the given ConverterRegistry could not be cast to a
	 * ConversionService
	 */
	public static void addApplicationConverters(ConverterRegistry registry) {
		addDelimitedStringConverters(registry);
		registry.addConverter(new StringToDurationConverter());
		registry.addConverter(new DurationToStringConverter());
		registry.addConverter(new NumberToDurationConverter());
		registry.addConverter(new DurationToNumberConverter());
		registry.addConverter(new StringToPeriodConverter());
		registry.addConverter(new PeriodToStringConverter());
		registry.addConverter(new NumberToPeriodConverter());
		registry.addConverter(new StringToDataSizeConverter());
		registry.addConverter(new NumberToDataSizeConverter());
		registry.addConverter(new StringToFileConverter());
		registry.addConverter(new InputStreamSourceToByteArrayConverter());
		registry.addConverterFactory(new LenientStringToEnumConverterFactory());
		registry.addConverterFactory(new LenientBooleanToEnumConverterFactory());
		if (registry instanceof ConversionService) {
			addApplicationConverters(registry, (ConversionService) registry);
		}
	}

	private static void addApplicationConverters(ConverterRegistry registry, ConversionService conversionService) {
		registry.addConverter(new CharSequenceToObjectConverter(conversionService));
	}

	/**
	 * Add converters to support delimited strings.
	 * @param registry the registry of converters to add to (must also be castable to
	 * ConversionService, e.g. being a {@link ConfigurableConversionService})
	 * @throws ClassCastException if the given ConverterRegistry could not be cast to a
	 * ConversionService
	 */
	public static void addDelimitedStringConverters(ConverterRegistry registry) {
		ConversionService service = (ConversionService) registry;
		registry.addConverter(new ArrayToDelimitedStringConverter(service));
		registry.addConverter(new CollectionToDelimitedStringConverter(service));
		registry.addConverter(new DelimitedStringToArrayConverter(service));
		registry.addConverter(new DelimitedStringToCollectionConverter(service));
	}

	/**
	 * Add formatters useful for most Spring Boot applications.
	 * @param registry the service to register default formatters with
	 */
	public static void addApplicationFormatters(FormatterRegistry registry) {
		registry.addFormatter(new CharArrayFormatter());
		registry.addFormatter(new InetAddressFormatter());
		registry.addFormatter(new IsoOffsetFormatter());
	}

	/**
	 * Add {@link GenericConverter}, {@link Converter}, {@link Printer}, {@link Parser}
	 * and {@link Formatter} beans from the specified context.
	 * @param registry the service to register beans with
	 * @param beanFactory the bean factory to get the beans from
	 * @since 2.2.0
	 */
	public static void addBeans(FormatterRegistry registry, ListableBeanFactory beanFactory) {
		Set<Map.Entry<String, ?>> entries = new LinkedHashSet<>();
		entries.addAll(beanFactory.getBeansOfType(GenericConverter.class).entrySet());
		entries.addAll(beanFactory.getBeansOfType(Converter.class).entrySet());
		entries.addAll(beanFactory.getBeansOfType(Printer.class).entrySet());
		entries.addAll(beanFactory.getBeansOfType(Parser.class).entrySet());
		for (Map.Entry<String, ?> entity : entries) {
			Object bean = entity.getValue();
			if (bean instanceof GenericConverter) {
				registry.addConverter((GenericConverter) bean);
			}
			else if (bean instanceof Converter) {
				addConverter(registry, beanFactory, entity);
			}
			else if (bean instanceof Formatter) {
				registry.addFormatter((Formatter<?>) bean);
			}
			else if (bean instanceof Printer) {
				registry.addPrinter((Printer<?>) bean);
			}
			else if (bean instanceof Parser) {
				registry.addParser((Parser<?>) bean);
			}
		}
	}

	private static void addConverter(FormatterRegistry registry, ListableBeanFactory beanFactory,
			Map.Entry<String, ?> entity) {
		try {
			registry.addConverter((Converter<?, ?>) entity.getValue());
		}
		catch (IllegalArgumentException ex) {
			boolean success = tryAddMethodSignatureGenericsConverter(registry, beanFactory, entity);
			if (!success) {
				throw ex;
			}
		}
	}

	private static boolean tryAddMethodSignatureGenericsConverter(FormatterRegistry registry,
			ListableBeanFactory beanFactory, Map.Entry<String, ?> entity) {
		ListableBeanFactory bf = beanFactory;
		if (bf instanceof ConfigurableApplicationContext) {
			bf = ((ConfigurableApplicationContext) bf).getBeanFactory();
		}
		if (bf instanceof ConfigurableListableBeanFactory) {
			ConfigurableListableBeanFactory clbf = (ConfigurableListableBeanFactory) bf;
			ConverterAdapter adapter = getConverterAdapter(clbf, entity);
			if (!Objects.isNull(adapter)) {
				registry.addConverter(adapter);
				return true;
			}
		}
		return false;
	}

	private static ConverterAdapter getConverterAdapter(ConfigurableListableBeanFactory beanFactory,
			Map.Entry<String, ?> beanEntity) {
		BeanDefinition beanDefinition = beanFactory.getMergedBeanDefinition(beanEntity.getKey());
		ResolvableType resolvableType = beanDefinition.getResolvableType();
		ResolvableType[] types = resolvableType.getGenerics();
		if (types.length < 2) {
			return null;
		}
		return new ConverterAdapter((Converter<?, ?>) beanEntity.getValue(), types[0], types[1]);
	}

	/**
	 * Adapts a {@link Converter} to a {@link GenericConverter}.
	 * <p>
	 * Reference from
	 * {@link org.springframework.core.convert.support.GenericConversionService.ConverterAdapter}
	 */
	@SuppressWarnings("unchecked")
	private static final class ConverterAdapter implements ConditionalGenericConverter {

		private final Converter<Object, Object> converter;

		private final ConvertiblePair typeInfo;

		private final ResolvableType targetType;

		ConverterAdapter(Converter<?, ?> converter, ResolvableType sourceType, ResolvableType targetType) {
			this.converter = (Converter<Object, Object>) converter;
			this.typeInfo = new ConvertiblePair(sourceType.toClass(), targetType.toClass());
			this.targetType = targetType;
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return Collections.singleton(this.typeInfo);
		}

		@Override
		public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
			// Check raw type first...
			if (this.typeInfo.getTargetType() != targetType.getObjectType()) {
				return false;
			}
			// Full check for complex generic type match required?
			ResolvableType rt = targetType.getResolvableType();
			if (!(rt.getType() instanceof Class) && !rt.isAssignableFrom(this.targetType)
					&& !this.targetType.hasUnresolvableGenerics()) {
				return false;
			}
			return !(this.converter instanceof ConditionalConverter)
					|| ((ConditionalConverter) this.converter).matches(sourceType, targetType);
		}

		@Override
		public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			if (source == null) {
				return convertNullSource(sourceType, targetType);
			}
			return this.converter.convert(source);
		}

		@Override
		public String toString() {
			return (this.typeInfo + " : " + this.converter);
		}

		/**
		 * Template method to convert a {@code null} source.
		 * <p>
		 * The default implementation returns {@code null} or the Java 8
		 * {@link java.util.Optional#empty()} instance if the target type is
		 * {@code java.util.Optional}. Subclasses may override this to return custom
		 * {@code null} objects for specific target types.
		 * @param sourceType the source type to convert from
		 * @param targetType the target type to convert to
		 * @return the converted null object
		 */
		private Object convertNullSource(TypeDescriptor sourceType, TypeDescriptor targetType) {
			if (targetType.getObjectType() == Optional.class) {
				return Optional.empty();
			}
			return null;
		}

	}

}
