/*
 * Copyright 2015-2018 floragunn GmbH
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.floragunn.searchguard.support;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;

public final class SgUtils {
    
    protected final static Logger log = LogManager.getLogger(SgUtils.class);
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{env\\.([\\w]+)([+-]?)\\}");
    
    private SgUtils() {
    }

    public static String evalMap(final Map<String,Set<String>> map, final String index) {

        if (map == null) {
            return null;
        }

        if (map.get(index) != null) {
            return index;
        } else if (map.get("*") != null) {
            return "*";
        }
        if (map.get("_all") != null) {
            return "_all";
        }

        //regex
        for(final String key: map.keySet()) {
            if(WildcardMatcher.containsWildcard(key)
                    && WildcardMatcher.match(key, index)) {
                return key;
            }
        }

        return null;
    }
    
    @SafeVarargs
    public static <T> Map<T, T>  mapFromArray(T ... keyValues) {
        if(keyValues == null) {
            return Collections.emptyMap();
        }
        if (keyValues.length % 2 != 0) {
            log.error("Expected even number of key/value pairs, got {}.", Arrays.toString(keyValues));
            return null;
        }
        Map<T, T> map = new HashMap<>();
        
        for(int i = 0; i<keyValues.length; i+=2) {
            map.put(keyValues[i], keyValues[i+1]);
        }
        return map;
    }
    
    public static String replaceEnvVars(String in) {
        //${env.MY_ENV_VAR}
        Matcher matcher = ENV_PATTERN.matcher(in);
        StringBuffer sb = new StringBuffer();
        while(matcher.find()) {
            final String replacement = resolveEnvVar(matcher.group(1), matcher.group(2));
            if(replacement != null) {
                matcher.appendReplacement(sb, replacement);
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    
    private static String resolveEnvVar(String envVarName, String mode) {
        final String envVarValue = System.getenv(envVarName);
        if(envVarValue == null || envVarValue.isEmpty()) {
            if("+".equals(mode)) {
                throw new ElasticsearchException(envVarName+" not defined");
            } else if("-".equals(mode)) {
                return "";
            } else {
                return null;
            }
        } else {
            return envVarValue;
        }
    }
    
    public static void main(String[] args) {
        System.out.println(replaceEnvVars("My land is a ${env.SHELL+} nice ${env.OOOL} with ${TZUJKL} and with ${user.name}"));
    }
}
