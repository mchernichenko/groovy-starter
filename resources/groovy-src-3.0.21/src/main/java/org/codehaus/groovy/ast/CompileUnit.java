/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.ast;

import groovy.lang.GroovyClassLoader;
import groovy.transform.Internal;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Represents the entire contents of a compilation step which consists of one or more
 * {@link ModuleNode} instances. There's one instance of this that's shared by all modules and
 * classes compiled during a single invocation of the compiler.
 * <p>
 * It's attached to MethodNodes and ClassNodes and is used to find fully qualified names of classes,
 * resolve imports, and that sort of thing.
 */
public class CompileUnit implements NodeMetaDataHandler {

    private final List<ModuleNode> modules = new ArrayList<>();
    private final Map<String, ClassNode> classes = new LinkedHashMap<>();
    private final CompilerConfiguration config;
    private final GroovyClassLoader classLoader;
    private final CodeSource codeSource;
    private final Map<String, ClassNode> classesToCompile = new LinkedHashMap<>();
    private final Map<String, SourceUnit> classNameToSource = new LinkedHashMap<>();
    private final Map<String, InnerClassNode> generatedInnerClasses = new LinkedHashMap<>();

    private Map metaDataMap;

    public CompileUnit(GroovyClassLoader classLoader, CompilerConfiguration config) {
        this(classLoader, null, config);
    }

    public CompileUnit(GroovyClassLoader classLoader, CodeSource codeSource, CompilerConfiguration config) {
        this.classLoader = classLoader;
        this.codeSource = codeSource;
        this.config = config;
    }

    public List<ModuleNode> getModules() {
        return modules;
    }

    public void addModule(ModuleNode node) {
        // node==null means a compilation error prevented
        // groovy from building an ast
        if (node == null) return;
        modules.add(node);
        node.setUnit(this);
        addClasses(node.getClasses());
    }

    /**
     * @return the ClassNode for the given qualified name or returns null if
     *         the name does not exist in the current compilation unit
     *         (ignoring the .class files on the classpath)
     */
    public ClassNode getClass(String name) {
        ClassNode cn = classes.get(name);
        if (cn != null) return cn;
        return classesToCompile.get(name);
    }

    /**
     * @return a list of all the classes in each module in the compilation unit
     */
    public List<ClassNode> getClasses() {
        List<ClassNode> answer = new ArrayList<ClassNode>();
        for (ModuleNode module : modules) {
            answer.addAll(module.getClasses());
        }
        return answer;
    }

    public CompilerConfiguration getConfig() {
        return config;
    }

    public GroovyClassLoader getClassLoader() {
        return classLoader;
    }

    public CodeSource getCodeSource() {
        return codeSource;
    }

    /**
     * Appends all of the fully qualified class names in this
     * module into the given map
     */
    void addClasses(List<ClassNode> classList) {
        for (ClassNode node : classList) {
            addClass(node);
        }
    }

    /**
     * Adds a class to the unit.
     */
    public void addClass(ClassNode node) {
        node = node.redirect();
        String name = node.getName();
        ClassNode stored = classes.get(name);
        if (stored != null && stored != node) {
            // we have a duplicate class!
            // One possibility for this is, that we declared a script and a
            // class in the same file and named the class like the file
            SourceUnit nodeSource = node.getModule().getContext();
            SourceUnit storedSource = stored.getModule().getContext();
            String txt = "Invalid duplicate class definition of class " + node.getName() + " : ";
            if (nodeSource == storedSource) {
                // same class in same source
                txt += "The source " + nodeSource.getName() + " contains at least two definitions of the class " + node.getName() + ".\n";
                if (node.isScriptBody() || stored.isScriptBody()) {
                    txt += "One of the classes is an explicit generated class using the class statement, the other is a class generated from" +
                            " the script body based on the file name. Solutions are to change the file name or to change the class name.\n";
                }
            } else {
                txt += "The sources " + nodeSource.getName() + " and " + storedSource.getName() + " each contain a class with the name " + node.getName() + ".\n";
            }
            nodeSource.getErrorCollector().addErrorAndContinue(
                    new SyntaxErrorMessage(new SyntaxException(txt, node.getLineNumber(), node.getColumnNumber(), node.getLastLineNumber(), node.getLastColumnNumber()), nodeSource)
            );
        }
        classes.put(name, node);

        ClassNode cn = classesToCompile.get(name);
        if (cn != null) {
            cn.setRedirect(node);
            classesToCompile.remove(name);
        }
    }

    /**
     * this method actually does not compile a class. It's only
     * a marker that this type has to be compiled by the CompilationUnit
     * at the end of a parse step no node should be be left.
     */
    public void addClassNodeToCompile(ClassNode node, SourceUnit location) {
        String nodeName = node.getName();
        classesToCompile.put(nodeName, node);
        classNameToSource.put(nodeName, location);
    }

    public Map<String, ClassNode> getClassesToCompile() {
        return classesToCompile;
    }

    public Iterator<String> iterateClassNodeToCompile() {
        return classesToCompile.keySet().iterator();
    }

    public boolean hasClassNodeToCompile() {
        return !classesToCompile.isEmpty();
    }

    public void addGeneratedInnerClass(InnerClassNode icn) {
        generatedInnerClasses.put(icn.getName(), icn);
    }

    public InnerClassNode getGeneratedInnerClass(String name) {
        return generatedInnerClasses.get(name);
    }

    public Map<String, InnerClassNode> getGeneratedInnerClasses() {
        return Collections.unmodifiableMap(generatedInnerClasses);
    }

    public SourceUnit getScriptSourceLocation(String scriptClassName) {
        return classNameToSource.get(scriptClassName);
    }

    @Override
    public Map<?, ?> getMetaDataMap() {
        return metaDataMap;
    }

    @Override
    public void setMetaDataMap(Map<?, ?> metaDataMap) {
        this.metaDataMap = metaDataMap;
    }

    //--------------------------------------------------------------------------

    private final Map<String, ConstructedOuterNestedClassNode> classesToResolve = new LinkedHashMap<>();

    /**
     * Add a constructed class node as a placeholder to resolve outer nested class further.
     *
     * @param cn the constructed class node
     */
    @Deprecated
    public void addClassNodeToResolve(ConstructedOuterNestedClassNode cn) {
        classesToResolve.put(cn.getUnresolvedName(), cn);
    }

    @Deprecated
    public Map<String, ConstructedOuterNestedClassNode> getClassesToResolve() {
        return classesToResolve;
    }

    /**
     * Represents a resolved type as a placeholder.
     *
     * @see <a href="https://issues.apache.org/jira/browse/GROOVY-7812">GROOVY-7812</a>
     */
    @Internal
    @Deprecated
    public static class ConstructedOuterNestedClassNode extends ClassNode {
        private final ClassNode enclosingClassNode;
        private final List<BiConsumer<ConstructedOuterNestedClassNode, ClassNode>> setRedirectListenerList = new ArrayList<>();

        public ConstructedOuterNestedClassNode(ClassNode outer, String innerClassName) {
            super(innerClassName, ACC_PUBLIC, ClassHelper.OBJECT_TYPE);
            this.enclosingClassNode = outer;
            this.isPrimaryNode = false;
        }

        public ClassNode getEnclosingClassNode() {
            return enclosingClassNode;
        }

        @Override
        public void setRedirect(ClassNode cn) {
            for (BiConsumer<ConstructedOuterNestedClassNode, ClassNode> setRedirectListener : setRedirectListenerList) {
                setRedirectListener.accept(this, cn);
            }
            super.setRedirect(cn);
        }

        public void addSetRedirectListener(BiConsumer<ConstructedOuterNestedClassNode, ClassNode> setRedirectListener) {
            setRedirectListenerList.add(setRedirectListener);
        }
    }
}
