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
import com.tonicsystems.jarjar.ext_util.*;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class JjMainProcessor implements JarProcessor
{
    private final boolean verbose;
    private final JarProcessorChain chain;
    private final Map<String, String> renames = new HashMap<String, String>();

    public JjMainProcessor(List<PatternElement> patterns, boolean verbose, boolean skipManifest) {
        this.verbose = verbose;
        List<Rule> ruleList = new ArrayList<Rule>();
        for (PatternElement pattern : patterns) {
            if (pattern instanceof Rule) {
                ruleList.add((Rule) pattern);
            }
        }

        JjPackageRemapper pr = new JjPackageRemapper(ruleList, verbose);

        List<JarProcessor> processors = new ArrayList<JarProcessor>();
        processors.add(new JarTransformerChain(new RemappingClassTransformer[]{ new RemappingClassTransformer(pr) }));
        chain = new JarProcessorChain(processors.toArray(new JarProcessor[processors.size()]));
    }

    public void strip(File file) throws IOException {
        return;
    }

    /**
     * Returns the <code>.class</code> files to delete. As well the root-parameter as the rename ones
     * are taken in consideration, so that the concerned files are not listed in the result.
     *
     * @return the paths of the files in the jar-archive, including the <code>.class</code> suffix
     */
    private Set<String> getExcludes() {
        return new HashSet<String>();
    }

    /**
     *
     * @param struct
     * @return <code>true</code> if the entry is to include in the output jar
     * @throws IOException
     */
    public boolean process(EntryStruct struct) throws IOException {
        String name = struct.name;
        boolean keepIt = chain.process(struct);
        if (keepIt) {
            if (!name.equals(struct.name)) {
                if (verbose)
                    System.err.println("Renamed " + name + " -> " + struct.name);
            }
        } else {
            if (verbose)
                System.err.println("Removed " + name);
        }
        return keepIt;
    }
}
