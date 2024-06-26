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
package org.codehaus.groovy.classgen;

import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PackageNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.AnnotationConstantExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.AnnotationConstantsVisitor;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.SourceUnit;
import org.objectweb.asm.Opcodes;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;
import static org.codehaus.groovy.ast.ClassHelper.makeCached;
import static org.codehaus.groovy.ast.tools.GeneralUtils.getInterfacesAndSuperInterfaces;
import static org.codehaus.groovy.ast.tools.GeneralUtils.listX;
import static org.codehaus.groovy.ast.tools.GenericsUtils.correctToGenericsSpec;
import static org.codehaus.groovy.ast.tools.GenericsUtils.correctToGenericsSpecRecurse;
import static org.codehaus.groovy.ast.tools.GenericsUtils.createGenericsSpec;
import static org.codehaus.groovy.ast.tools.ParameterUtils.parametersEqual;
import static org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport.evaluateExpression;

/**
 * A specialized Groovy AST visitor meant to perform additional verifications upon the
 * current AST. Currently it does checks on annotated nodes and annotations itself.
 * <p>
 * Current limitations:
 * - annotations on local variables are not supported
 */
public class ExtendedVerifier extends ClassCodeVisitorSupport {
    public static final String JVM_ERROR_MESSAGE = "Please make sure you are running on a JVM >= 1.5";

    private final SourceUnit source;
    private ClassNode currentClass;

    public ExtendedVerifier(SourceUnit sourceUnit) {
        this.source = sourceUnit;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return this.source;
    }

    @Override
    public void visitClass(ClassNode node) {
        AnnotationConstantsVisitor acv = new AnnotationConstantsVisitor();
        acv.visitClass(node, this.source);
        this.currentClass = node;
        if (node.isAnnotationDefinition()) {
            visitAnnotations(node, AnnotationNode.ANNOTATION_TARGET);
        } else {
            visitAnnotations(node, AnnotationNode.TYPE_TARGET);
        }
        PackageNode packageNode = node.getPackage();
        if (packageNode != null) {
            visitAnnotations(packageNode, AnnotationNode.PACKAGE_TARGET);
        }
        node.visitContents(this);
    }

    @Override
    public void visitField(FieldNode node) {
        visitAnnotations(node, AnnotationNode.FIELD_TARGET);
    }

    @Override
    public void visitDeclarationExpression(DeclarationExpression expression) {
        visitAnnotations(expression, AnnotationNode.LOCAL_VARIABLE_TARGET);
    }

    @Override
    public void visitConstructor(ConstructorNode node) {
        visitConstructorOrMethod(node, AnnotationNode.CONSTRUCTOR_TARGET);
    }

    @Override
    public void visitMethod(MethodNode node) {
        visitConstructorOrMethod(node, AnnotationNode.METHOD_TARGET);
    }

    private void visitConstructorOrMethod(MethodNode node, int methodTarget) {
        visitAnnotations(node, methodTarget);
        for (Parameter parameter : node.getParameters()) {
            visitAnnotations(parameter, AnnotationNode.PARAMETER_TARGET);
        }

        if (this.currentClass.isAnnotationDefinition() && !node.isStaticConstructor()) {
            ErrorCollector errorCollector = new ErrorCollector(this.source.getConfiguration());
            AnnotationVisitor visitor = new AnnotationVisitor(this.source, errorCollector);
            visitor.setReportClass(this.currentClass);
            visitor.checkReturnType(node.getReturnType(), node);
            if (node.getParameters().length > 0) {
                addError("Annotation members may not have parameters.", node.getParameters()[0]);
            }
            if (node.getExceptions().length > 0) {
                addError("Annotation members may not have a throws clause.", node.getExceptions()[0]);
            }
            ReturnStatement code = (ReturnStatement) node.getCode();
            if (code != null) {
                visitor.visitExpression(node.getName(), code.getExpression(), node.getReturnType());
                visitor.checkCircularReference(this.currentClass, node.getReturnType(), code.getExpression());
            }
            this.source.getErrorCollector().addCollectorContents(errorCollector);
        }
        Statement code = node.getCode();
        if (code != null) {
            code.visit(this);
        }
    }

    @Override
    public void visitProperty(PropertyNode node) {
    }

    protected void visitAnnotations(AnnotatedNode node, int target) {
        if (node.getAnnotations().isEmpty()) {
            return;
        }
        this.currentClass.setAnnotated(true);
        if (!isAnnotationCompatible()) {
            addError("Annotations are not supported in the current runtime. " + JVM_ERROR_MESSAGE, node);
            return;
        }
        Map<String, List<AnnotationNode>> nonSourceAnnotations = new LinkedHashMap<>();
        for (AnnotationNode unvisited : node.getAnnotations()) {
            AnnotationNode visited;
            {
                ErrorCollector errorCollector = new ErrorCollector(source.getConfiguration());
                AnnotationVisitor visitor = new AnnotationVisitor(source, errorCollector);
                visited = visitor.visit(unvisited);
                source.getErrorCollector().addCollectorContents(errorCollector);
            }

            String name = visited.getClassNode().getName();
            if (!visited.hasSourceRetention()) {
                List<AnnotationNode> seen = nonSourceAnnotations.get(name);
                if (seen == null) {
                    seen = new ArrayList<>();
                }
                seen.add(visited);
                nonSourceAnnotations.put(name, seen);
            }

            // Check if the annotation target is correct, unless it's the target annotating an annotation definition
            // defining on which target elements the annotation applies
            boolean isTargetAnnotation = name.equals("java.lang.annotation.Target");
            if (!isTargetAnnotation && !visited.isTargetAllowed(target)) {
                addError("Annotation @" + name + " is not allowed on element " + AnnotationNode.targetToName(target), visited);
            }
            visitDeprecation(node, visited);
            visitOverride(node, visited);
        }
        checkForDuplicateAnnotations(node, nonSourceAnnotations);
    }

    private void checkForDuplicateAnnotations(AnnotatedNode node, Map<String, List<AnnotationNode>> nonSourceAnnotations) {
        for (Map.Entry<String, List<AnnotationNode>> entry : nonSourceAnnotations.entrySet()) {
            if (entry.getValue().size() > 1) {
                ClassNode repeatable = null;
                AnnotationNode repeatee = entry.getValue().get(0);
                for (AnnotationNode anno : repeatee.getClassNode().getAnnotations()) {
                    if (anno.getClassNode().getName().equals("java.lang.annotation.Repeatable")) {
                        Expression value = anno.getMember("value");
                        if (value instanceof ClassExpression && value.getType().isAnnotationDefinition()) {
                            repeatable = value.getType();
                            break;
                        }
                    }
                }
                if (repeatable != null) {
                    if (nonSourceAnnotations.containsKey(repeatable.getName())) {
                        addError("Cannot specify duplicate annotation on the same member. Explicit " + repeatable.getName() + " found when creating implicit container for " + entry.getKey(), node);
                    }
                    AnnotationNode collector = new AnnotationNode(repeatable);
                    if (repeatee.hasClassRetention()) {
                        collector.setClassRetention(true);
                    } else if (repeatee.hasRuntimeRetention()) {
                        collector.setRuntimeRetention(true);
                    } else { // load retention policy from annotation definition
                        List<AnnotationNode> retention = repeatable.getAnnotations(makeCached(Retention.class));
                        if (!retention.isEmpty()) {
                            Object policy;
                            Expression value = retention.get(0).getMember("value");
                            if (value instanceof PropertyExpression) {
                                policy = ((PropertyExpression) value).getPropertyAsString();
                            } else { // NOTE: it is risky to evaluate the expression from repeatable's source this way:
                                policy = evaluateExpression(value, source.getConfiguration(), source.getClassLoader());
                            }
                            if ("CLASS".equals(policy)) {
                                collector.setClassRetention(true);
                            } else if ("RUNTIME".equals(policy)) {
                                collector.setRuntimeRetention(true);
                            }
                        }
                    }
                    collector.addMember("value", listX(entry.getValue().stream().map(AnnotationConstantExpression::new).collect(toList())));
                    node.getAnnotations().removeAll(entry.getValue());
                    node.addAnnotation(collector);
                }
            }
        }
    }

    private static void visitDeprecation(AnnotatedNode node, AnnotationNode visited) {
        if (visited.getClassNode().isResolved() && visited.getClassNode().getName().equals("java.lang.Deprecated")) {
            if (node instanceof MethodNode) {
                MethodNode mn = (MethodNode) node;
                mn.setModifiers(mn.getModifiers() | Opcodes.ACC_DEPRECATED);
            } else if (node instanceof FieldNode) {
                FieldNode fn = (FieldNode) node;
                fn.setModifiers(fn.getModifiers() | Opcodes.ACC_DEPRECATED);
            } else if (node instanceof ClassNode) {
                ClassNode cn = (ClassNode) node;
                cn.setModifiers(cn.getModifiers() | Opcodes.ACC_DEPRECATED);
            }
        }
    }

    // TODO GROOVY-5011 handle case of @Override on a property
    private void visitOverride(AnnotatedNode node, AnnotationNode visited) {
        ClassNode annotationType = visited.getClassNode();
        if (annotationType.isResolved() && annotationType.getName().equals("java.lang.Override")) {
            if (node instanceof MethodNode && !Boolean.TRUE.equals(node.getNodeMetaData(Verifier.DEFAULT_PARAMETER_GENERATED))) {
                boolean override = false;
                MethodNode origMethod = (MethodNode) node;
                ClassNode cNode = origMethod.getDeclaringClass();
                if (origMethod.hasDefaultValue()) {
                    List<MethodNode> variants = cNode.getDeclaredMethods(origMethod.getName());
                    for (MethodNode m : variants) {
                        if (m.getAnnotations().contains(visited) && isOverrideMethod(m)) {
                            override = true;
                            break;
                        }
                    }
                } else {
                    override = isOverrideMethod(origMethod);
                }

                if (!override) {
                    addError("Method '" + origMethod.getName() + "' from class '" + cNode.getName() + "' does not override " +
                            "method from its superclass or interfaces but is annotated with @Override.", visited);
                }
            }
        }
    }

    private static boolean isOverrideMethod(final MethodNode method) {
        ClassNode declaringClass = method.getDeclaringClass();
        ClassNode next = declaringClass;
        outer:
        while (next != null) {
            Map<String, ClassNode> nextSpec = createGenericsSpec(next);
            MethodNode mn = correctToGenericsSpec(nextSpec, method);
            if (next != declaringClass) {
                if (getDeclaredMethodCorrected(nextSpec, mn, next) != null) break;
            }

            for (ClassNode face : getInterfacesAndSuperInterfaces(next)) {
                Map<String, ClassNode> faceSpec = createGenericsSpec(face, nextSpec);
                if (getDeclaredMethodCorrected(faceSpec, mn, face) != null) break outer;
            }

            ClassNode superClass = next.getUnresolvedSuperClass();
            if (superClass != null) {
                next = correctToGenericsSpecRecurse(nextSpec, superClass);
            } else {
                next = null;
            }
        }
        return next != null;
    }

    private static MethodNode getDeclaredMethodCorrected(final Map<String, ClassNode> genericsSpec, final MethodNode mn, final ClassNode cn) {
        for (MethodNode declared : cn.getDeclaredMethods(mn.getName())) {
            MethodNode corrected = correctToGenericsSpec(genericsSpec, declared);
            if (parametersEqual(corrected.getParameters(), mn.getParameters())) {
                return corrected;
            }
        }
        return null;
    }

    /**
     * Check if the current runtime allows Annotation usage.
     *
     * @return true if running on a 1.5+ runtime
     */
    protected boolean isAnnotationCompatible() {
        return CompilerConfiguration.isPostJDK5(this.source.getConfiguration().getTargetBytecode());
    }
}
