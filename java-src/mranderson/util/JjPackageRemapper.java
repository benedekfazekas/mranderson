/**
 * Copied from Jar Jar Links 1.4
 *
 * Original licence
 *
 * Copyright 2007 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mranderson.util;

import com.tonicsystems.jarjar.*;
import com.tonicsystems.jarjar.asm.*;
import com.tonicsystems.jarjar.asm.commons.*;
import java.util.*;
import java.util.regex.Pattern;

public class JjPackageRemapper extends Remapper
{
    private static final String RESOURCE_SUFFIX = "RESOURCE";

    private static final Pattern ARRAY_FOR_NAME_PATTERN
        = Pattern.compile("\\[L[\\p{javaJavaIdentifierPart}\\.]+?;");

    private final List<JjWildcard> wildcards;
    private final Map<String, String> typeCache = new HashMap<String, String>();
    private final Map<String, String> pathCache = new HashMap<String, String>();
    private final Map<Object, String> valueCache = new HashMap<Object, String>();
    private final boolean verbose;

    private static List<JjWildcard> createWildcards(List<? extends PatternElement> patterns) {
        List<JjWildcard> wildcards = new ArrayList<JjWildcard>();
        for (PatternElement pattern : patterns) {
            String result = (pattern instanceof Rule) ? ((Rule)pattern).getResult() : "";
            String expr = pattern.getPattern();
            if (expr.indexOf('/') >= 0)
                throw new IllegalArgumentException("Patterns cannot contain slashes");
            wildcards.add(new JjWildcard(expr.replace('.', '/'), result));
        }
        return wildcards;
    }

    public JjPackageRemapper(List<Rule> ruleList, boolean verbose) {
        this.verbose = verbose;
        wildcards = createWildcards(ruleList);
    }

    // also used by KeepProcessor
    static boolean isArrayForName(String value) {
      return ARRAY_FOR_NAME_PATTERN.matcher(value).matches();
    }

    public String map(String key) {
        String s = typeCache.get(key);
        if (s == null) {
            s = replaceHelper(key);
            if (key.equals(s))
                s = null;
            typeCache.put(key, s);
        }
        return s;
    }

    public String mapPath(String path) {
        String s = pathCache.get(path);
        if (s == null) {
            s = path;
            int slash = s.lastIndexOf('/');
            String end;
            if (slash < 0) {
                end = s;
                s = RESOURCE_SUFFIX;
            } else {
                end = s.substring(slash + 1);
                s = s.substring(0, slash + 1) + RESOURCE_SUFFIX;
            }
            boolean absolute = s.startsWith("/");
            if (absolute) s = s.substring(1);

            s = replaceHelper(s);

            if (absolute) s = "/" + s;
            if (s.indexOf(RESOURCE_SUFFIX) < 0)
              return path;
            s = s.substring(0, s.length() - RESOURCE_SUFFIX.length()) + end;
            pathCache.put(path, s);
        }
        return s;
    }

    public Object mapValue(Object value) {
        if (value instanceof String) {
            String s = valueCache.get(value);
            if (s == null) {
                s = (String)value;
                if (isArrayForName(s)) {
                    String desc1 = s.replace('.', '/');
                    String desc2 = mapDesc(desc1);
                    if (!desc2.equals(desc1))
                        return desc2.replace('/', '.');
                } else {
                    s = mapPath(s);
                    if (s.equals(value)) {
                        boolean hasDot = s.indexOf('.') >= 0;
                        boolean hasSlash = s.indexOf('/') >= 0;
                        if (!(hasDot && hasSlash)) {
                            if (hasDot) {
                                s = replaceHelper(s.replace('.', '/')).replace('/', '.');
                            } else {
                                s = replaceHelper(s);
                            }
                        }
                    }
                }
                valueCache.put(value, s);
            }
            // TODO: add back class name to verbose message
            if (verbose && !s.equals(value))
                System.err.println("Changed \"" + value + "\" -> \"" + s + "\"");
            return s;
        } else {
            return super.mapValue(value);
        }
    }

    private String replaceHelper(String value) {
        for (JjWildcard wildcard : wildcards) {
            String test = wildcard.replace(value);
            if (test != null)
                return test;
        }
        return value;
    }
}
