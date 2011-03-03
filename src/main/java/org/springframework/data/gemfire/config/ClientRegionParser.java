/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.gemfire.config;

import java.util.List;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.data.gemfire.RegionAttributesFactoryBean;
import org.springframework.data.gemfire.client.ClientRegionFactoryBean;
import org.springframework.data.gemfire.client.Interest;
import org.springframework.data.gemfire.client.RegexInterest;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;

import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.Scope;

/**
 * Parser for &lt;client-region;gt; definitions.
 * 
 * To avoid eager evaluations, the region interests are declared as nested definition.
 * 
 * @author Costin Leau
 */
class ClientRegionParser extends AbstractSingleBeanDefinitionParser {

	protected Class<?> getBeanClass(Element element) {
		return ClientRegionFactoryBean.class;
	}

	@Override
	protected void doParse(Element element, ParserContext parserContext, BeanDefinitionBuilder builder) {
		super.doParse(element, builder);

		// set scope
		// since the user can define both client and p2p regions
		// setting the cache/DS to a be 'loner' isn't feasible
		// so to prevent both client and p2p communication in the region,
		// the scope is fixed to local
		builder.addPropertyValue("scope", Scope.LOCAL);

		ParsingUtils.setPropertyValue(element, builder, "data-policy", "dataPolicy");
		ParsingUtils.setPropertyValue(element, builder, "name", "name");
		ParsingUtils.setPropertyValue(element, builder, "pool-name", "poolName");
		ParsingUtils.setPropertyReference(element, builder, "pool-ref", "pool");

		// set the persistent policy
		String attr = element.getAttribute("persistent");

		boolean frozenDataPolicy = false;

		if (Boolean.parseBoolean(attr)) {
			// check first for GemFire 6.5
			if (ConcurrentMap.class.isAssignableFrom(Region.class)) {
				builder.addPropertyValue("dataPolicy", DataPolicy.PERSISTENT_REPLICATE);
				frozenDataPolicy = true;
			}
			else {
				parserContext.getReaderContext().error(
						"Can define persistent partitions only from GemFire 6.5 onwards - current version is ["
								+ CacheFactory.getVersion() + "]", element);
			}
		}

		attr = element.getAttribute("cache-ref");
		// add cache reference (fallback to default if nothing is specified)
		builder.addPropertyReference("cache", (StringUtils.hasText(attr) ? attr : "gemfire-cache"));

		// eviction + overflow attributes
		// client attributes
		BeanDefinitionBuilder attrBuilder = BeanDefinitionBuilder.genericBeanDefinition(RegionAttributesFactoryBean.class);

		boolean overwriteDataPolicy = false;

		overwriteDataPolicy |= ParsingUtils.parseEviction(parserContext, element, attrBuilder);
		overwriteDataPolicy |= ParsingUtils.parseDiskStorage(element, attrBuilder);

		if (!frozenDataPolicy && overwriteDataPolicy) {
			builder.addPropertyValue("dataPolicy", DataPolicy.NORMAL);
		}

		// add partition/overflow settings as attributes
		builder.addPropertyValue("attributes", attrBuilder.getBeanDefinition());

		ManagedList<Object> interests = new ManagedList<Object>();

		// parse nested declarations
		List<Element> subElements = DomUtils.getChildElements(element);

		// parse nested cache-listener elements
		for (Element subElement : subElements) {
			String name = subElement.getLocalName();

			if ("cache-listener".equals(name)) {
				builder.addPropertyValue("cacheListeners", parseCacheListener(parserContext, subElement, builder));
			}

			else if ("key-interest".equals(name)) {
				interests.add(parseKeyInterest(parserContext, subElement));
			}

			else if ("regex-interest".equals(name)) {
				interests.add(parseRegexInterest(parserContext, subElement));
			}
		}

		if (!subElements.isEmpty()) {
			builder.addPropertyValue("interests", interests);
		}
	}

	private Object parseCacheListener(ParserContext parserContext, Element subElement, BeanDefinitionBuilder builder) {
		return ParsingUtils.parseRefOrNestedBeanDeclaration(parserContext, subElement, builder);
	}

	private Object parseKeyInterest(ParserContext parserContext, Element subElement) {
		BeanDefinitionBuilder keyInterestBuilder = BeanDefinitionBuilder.genericBeanDefinition(Interest.class);
		parseCommonInterestAttr(subElement, keyInterestBuilder);

		Object key = ParsingUtils.parseRefOrNestedBeanDeclaration(parserContext, subElement, keyInterestBuilder,
				"key-ref");
		keyInterestBuilder.addConstructorArgValue(key);
		return keyInterestBuilder.getBeanDefinition();
	}

	private Object parseRegexInterest(ParserContext parserContext, Element subElement) {
		BeanDefinitionBuilder regexInterestBuilder = BeanDefinitionBuilder.genericBeanDefinition(RegexInterest.class);

		parseCommonInterestAttr(subElement, regexInterestBuilder);
		ParsingUtils.setPropertyValue(subElement, regexInterestBuilder, "pattern", "key");

		return regexInterestBuilder.getBeanDefinition();
	}

	private void parseCommonInterestAttr(Element element, BeanDefinitionBuilder builder) {
		ParsingUtils.setPropertyValue(element, builder, "durable", "durable");
		ParsingUtils.setPropertyValue(element, builder, "result-policy", "policy");
	}
}