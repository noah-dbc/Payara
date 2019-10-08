/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2017-2018 Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.nucleus.microprofile.config.spi;

import fish.payara.nucleus.microprofile.config.converters.CommonSenseConverter;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;

import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author Steve Millidge (Payara Foundation)
 */
public class PayaraConfig implements Config {
    
    private final List<ConfigSource> configSources;
    private final Map<Type, Converter> converters;

    public PayaraConfig(List<ConfigSource> configSources, Map<Type,Converter> convertersMap) {
        this.configSources = configSources;
        this.converters = new ConcurrentHashMap<>();
        this.converters.putAll(convertersMap);
        Collections.sort(configSources, new ConfigSourceComparator());
    }

    @Override
    public <T> T getValue(String propertyName, Class<T> propertyType) {
        String result = getValue(propertyName);
        
        if (result == null) {
            throw new NoSuchElementException("Unable to find property with name " + propertyName);
        }
        return convertString(result, propertyType);
    }

    @Override
    public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
        String strValue = getValue(propertyName);
        
        if(String.class.equals(propertyType)) {
            return (Optional<T>) Optional.ofNullable(strValue);
        }

        if (strValue == null) {
            return Optional.empty();
        } else {
            return Optional.ofNullable(convertString(strValue, propertyType));
        }
    }

    @Override
    public Iterable<String> getPropertyNames() {
        LinkedList<String> result = new LinkedList<>();
        for (ConfigSource configSource : configSources) {
            result.addAll(configSource.getProperties().keySet());
        }
        return result;
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return configSources;
    }
    
    public Set<Type> getConverterTypes() {
        return converters.keySet();
    }  
        
    public <T> List<T> getListValues(String propertyName, String defaultValue, Class<T> elementType) {
        String value = getValue(propertyName);
        if (value == null) {
            value = defaultValue;
        }
        if (value == null) {
            throw new NoSuchElementException("Unable to find property with name " + propertyName);
        }
        String keys[] = splitValue(value);
        List<T> result = new ArrayList<>(keys.length);
        for (String key : keys) {
            result.add(convertString(key, elementType));
        }
        return result;
    }
    
    public <T> Set<T> getSetValues(String propertyName, String defaultValue, Class<T> elementType) {
        String value = getValue(propertyName);
        if (value == null) {
            value = defaultValue;
        }
        if (value == null) {
            throw new NoSuchElementException("Unable to find property with name " + propertyName);
        }
        String keys[] = splitValue(value);
        Set<T> result = new HashSet<>(keys.length);
        for (String key : keys) {
            result.add(convertString(key, elementType));
        }
        return result;
    }

    
    public <T> T getValue(String propertyName, String defaultValue, Class<T>  propertyType) {
        String result = getValue(propertyName);
        if (result == null) {
            result = defaultValue;
        }
        if (result == null) {
            throw new NoSuchElementException("Unable to find property with name " + propertyName);
        }
        return convertString(result, propertyType);
    }

    private String getValue(String propertyName) {
        String result = null;
        for (ConfigSource configSource : configSources) {
            result = configSource.getValue(propertyName);
            if (result != null) {
                break;
            }
        }
        return result;
    }
    
    private <T> Converter<T> getConverter(Class<T> propertyType) {
        Class type = boxedTypeOf(propertyType);

        Converter<T> converter = converters.get(type);

        if (converter != null) {
            return converter;
        }
            
        // search for a matching raw type
        for (Entry<Type, Converter> entry : converters.entrySet()) {
            Type type1 = entry.getKey();
            if (type1 instanceof ParameterizedType) {
                ParameterizedType ptype = (ParameterizedType)type1;
                if (ptype.getRawType().equals(propertyType)) {
                    return entry.getValue();
                }
            }
        }
        
        // see if a common sense converter can be created
        Optional<Converter<T>> commonSenseConverter = CommonSenseConverter.forClass(propertyType);
        if (commonSenseConverter.isPresent()) {
            converters.put(propertyType, commonSenseConverter.get());
            return commonSenseConverter.get();
        }

        throw new IllegalArgumentException("Unable to convert value to type " + propertyType.getCanonicalName());
    }



    private static <T> Class<T> boxedTypeOf(Class<T> type) {
        if (!type.isPrimitive()) {
            return type;
        } else if (type.equals(int.class)) {
            return (Class<T>) Integer.class;
        } else if (type.equals(boolean.class)) {
            return (Class<T>) Boolean.class;
        } else if (type.equals(long.class)) {
            return (Class<T>) Long.class;
        } else if (type.equals(double.class)) {
            return (Class<T>) Double.class;
        } else if (type.equals(float.class)) {
            return (Class<T>) Float.class;
        } else if (type.equals(byte.class)) {
            return (Class<T>) Byte.class;
        } else if (type.equals(char.class)) {
            return (Class<T>) Character.class;
        } else if (type.equals(short.class)) {
            return (Class<T>) Short.class;
        }
        return type;
    }

    private <T> T convertString(String value, Class<T> propertyType) {

        // if it is an array convert arrays
        if (propertyType.isArray()) {
            // find converter for the array type
            Class componentClazz = propertyType.getComponentType();
            Converter converter = getConverter(componentClazz);

            // array convert
            String keys[] = splitValue(value);
            Object arrayResult = Array.newInstance(componentClazz, keys.length);
            for (int i = 0; i < keys.length; i++) {
                Array.set(arrayResult, i, converter.convert(keys[i]));
            }
            return (T) arrayResult;
        } else {

            // find a converter
            Converter<T> converter = getConverter(propertyType);
            if (converter == null) {
                throw new IllegalArgumentException("No converter for class " + propertyType);
            }
            return converter.convert(value);
        }
    }
    
    private String[] splitValue(String value) {
        String keys[] = value.split("(?<!\\\\),");
        for (int i=0; i < keys.length; i++) {
            keys[i] = keys[i].replaceAll("\\\\,", ",");
        }
        return keys;
    }
    
}
