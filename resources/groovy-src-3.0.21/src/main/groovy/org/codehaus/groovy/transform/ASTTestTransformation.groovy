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
package org.codehaus.groovy.transform

import groovy.transform.CompilationUnitAware
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.Janitor
import org.codehaus.groovy.control.ProcessingUnit
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.ImportCustomizer
import org.codehaus.groovy.control.io.ReaderSource
import org.codehaus.groovy.runtime.MethodClosure
import org.codehaus.groovy.syntax.SyntaxException
import org.codehaus.groovy.tools.Utilities

import static org.codehaus.groovy.ast.tools.GeneralUtils.classX
import static org.codehaus.groovy.ast.tools.GeneralUtils.propX

@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
class ASTTestTransformation implements ASTTransformation, CompilationUnitAware {

    CompilationUnit compilationUnit

    @Override
    void visit(final ASTNode[] nodes, final SourceUnit source) {
        AnnotationNode annotationNode = nodes[0]
        def member = annotationNode.getMember('phase')
        CompilePhase phase = null
        if (member) {
            if (member instanceof VariableExpression) {
                phase = CompilePhase.valueOf(member.text)
            } else if (member instanceof PropertyExpression) {
                phase = CompilePhase.valueOf(member.propertyAsString)
            }
            annotationNode.setMember('phase', propX(classX(ClassHelper.make(CompilePhase)), phase.toString()))
        }
        member = annotationNode.getMember('value')
        if (member && !(member instanceof ClosureExpression)) {
            throw new SyntaxException('ASTTest value must be a closure', member.lineNumber, member.columnNumber)
        }
        if (!member && !annotationNode.getNodeMetaData(ASTTestTransformation)) {
            throw new SyntaxException('Missing test expression', annotationNode.lineNumber, annotationNode.columnNumber)
        }
        // convert value into node metadata so that the expression doesn't mix up with other AST xforms like STC
        annotationNode.setNodeMetaData(ASTTestTransformation, member)
        annotationNode.members.remove('value')

        def pcallback = compilationUnit.progressCallback
        def callback = new CompilationUnit.ProgressCallback() {
            private final Binding binding = new Binding([:].withDefault { null })

            @Override
            void call(final ProcessingUnit context, final int phaseNumber) {
                if (phase == null || phaseNumber == phase.phaseNumber) {
                    ClosureExpression testClosure = nodes[0].getNodeMetaData(ASTTestTransformation)
                    StringBuilder sb = new StringBuilder()
                    for (int i = testClosure.lineNumber; i <= testClosure.lastLineNumber; i += 1) {
                        sb.append(source.source.getLine(i, new Janitor())).append('\n')
                    }
                    def testSource = sb[testClosure.columnNumber..<sb.length()]
                    testSource = testSource[0..<testSource.lastIndexOf('}')]

                    binding['node'] = nodes[1]
                    binding['sourceUnit'] = source
                    binding['compilationUnit'] = compilationUnit
                    binding['compilePhase'] = CompilePhase.fromPhaseNumber(phaseNumber)
                    binding['lookup'] = new MethodClosure(LabelFinder, 'lookup').curry(nodes[1])

                    def customizer = new ImportCustomizer()
                    source.AST.imports.each {
                        customizer.addImport(it.alias, it.type.name)
                    }
                    source.AST.starImports.each {
                        customizer.addStarImports(it.packageName)
                    }
                    source.AST.staticImports.each {
                        customizer.addStaticImport(it.value.alias, it.value.type.name, it.value.fieldName)
                    }
                    source.AST.staticStarImports.each {
                        customizer.addStaticStars(it.value.className)
                    }

                    def config = new CompilerConfiguration()
                    config.addCompilationCustomizers(customizer)
                    def loader = compilationUnit.transformLoader
                    new GroovyShell(loader, binding, config).evaluate(testSource)
                }
            }
        }

        if (pcallback != null) {
            if (pcallback instanceof ProgressCallbackChain) {
                pcallback.addCallback(callback)
            } else {
                pcallback = new ProgressCallbackChain(pcallback, callback)
            }
            callback = pcallback
        }

        compilationUnit.progressCallback = callback
    }

    private static class AssertionSourceDelegatingSourceUnit extends SourceUnit {
        private final ReaderSource delegate

        AssertionSourceDelegatingSourceUnit(final String name, final ReaderSource source, final CompilerConfiguration config, final GroovyClassLoader loader, final ErrorCollector er) {
            super(name, '', config, loader, er)
            delegate = source
        }

        @Override
        String getSample(final int line, final int column, final Janitor janitor) {
            String sample = null
            String text = delegate.getLine(line, janitor)

            if (text != null) {
                if (column > 0) {
                    String marker = Utilities.repeatString(' ', column - 1) + '^'

                    if (column > 40) {
                        int start = column - 30 - 1
                        int end = (column + 10 > text.length() ? text.length() : column + 10 - 1)
                        sample = '   ' + text[start..<end] + Utilities.eol() + '   ' + marker[start..<marker.length()]
                    } else {
                        sample = '   ' + text + Utilities.eol() + '   ' + marker
                    }
                } else {
                    sample = text
                }
            }
            sample
        }
    }

    private static class ProgressCallbackChain implements CompilationUnit.ProgressCallback {
        private final List<CompilationUnit.ProgressCallback> chain = [] as LinkedList

        ProgressCallbackChain(final CompilationUnit.ProgressCallback... callbacks) {
            if (callbacks) {
                callbacks.each { addCallback(it) }
            }
        }

        void addCallback(final CompilationUnit.ProgressCallback callback) {
            chain << callback
        }

        @Override
        void call(final ProcessingUnit context, final int phase) {
            chain*.call(context, phase)
        }
    }

    static class LabelFinder extends ClassCodeVisitorSupport {

        static List<Statement> lookup(final MethodNode node, final String label) {
            LabelFinder finder = new LabelFinder(label, null)
            node.code.visit(finder)

            finder.targets
        }

        static List<Statement> lookup(final ClassNode node, final String label) {
            LabelFinder finder = new LabelFinder(label, null)
            node.methods*.code*.visit(finder)
            node.declaredConstructors*.code*.visit(finder)

            finder.targets
        }

        private final String label
        private final SourceUnit unit
        private final List<Statement> targets = [] as LinkedList

        LabelFinder(final String label, final SourceUnit unit) {
            this.label = label
            this.unit = unit
        }

        @Override
        protected SourceUnit getSourceUnit() {
            unit
        }

        @Override
        protected void visitStatement(final Statement statement) {
            super.visitStatement(statement)
            if (label in statement.statementLabels) targets << statement
        }

        List<Statement> getTargets() {
            Collections.unmodifiableList(targets)
        }
    }
}
