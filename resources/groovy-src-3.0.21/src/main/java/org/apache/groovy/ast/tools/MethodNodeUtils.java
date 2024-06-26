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
package org.apache.groovy.ast.tools;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.Statement;

import static org.apache.groovy.util.BeanUtils.decapitalize;

/**
 * Utility class for working with MethodNodes
 */
public class MethodNodeUtils {

    private MethodNodeUtils() { }

    /**
     * Return the method node's descriptor including its
     * name and parameter types without generics.
     *
     * @param mNode the method node
     * @return the method node's abbreviated descriptor excluding the return type
     */
    public static String methodDescriptorWithoutReturnType(final MethodNode mNode) {
        StringBuilder sb = new StringBuilder();
        sb.append(mNode.getName()).append(':');
        for (Parameter p : mNode.getParameters()) {
            sb.append(ClassNodeUtils.formatTypeName(p.getType())).append(',');
        }
        return sb.toString();
    }

    /**
     * Return the method node's descriptor which includes its return type,
     * name and parameter types without generics.
     *
     * @param mNode the method node
     * @return the method node's descriptor
     */
    public static String methodDescriptor(final MethodNode mNode) {
        String name = mNode.getName();
        Parameter[] parameters = mNode.getParameters();
        int nParameters = parameters == null ? 0 : parameters.length;

        StringBuilder sb = new StringBuilder(name.length() * 2 + nParameters * 10);
        sb.append(ClassNodeUtils.formatTypeName(mNode.getReturnType()));
        sb.append(' ');
        sb.append(name);
        sb.append('(');
        for (int i = 0; i < nParameters; i += 1) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ClassNodeUtils.formatTypeName(parameters[i].getType()));
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * For a method node potentially representing a property, returns the name of the property.
     *
     * @param mNode a MethodNode
     * @return the property name without the get/set/is prefix if a property or null
     */
    public static String getPropertyName(final MethodNode mNode) {
        final String name = mNode.getName();
        final int nameLength = name.length();
        if (nameLength > 2) {
            switch (name.charAt(0)) {
              case 'g':
                if (nameLength > 3 && name.charAt(1) == 'e' && name.charAt(2) == 't' && mNode.getParameters().length == 0 && !mNode.getReturnType().equals(ClassHelper.VOID_TYPE)) {
                    return decapitalize(name.substring(3));
                }
                break;
              case 's':
                if (nameLength > 3 && name.charAt(1) == 'e' && name.charAt(2) == 't' && mNode.getParameters().length == 1 /*&& mNode.getReturnType().equals(ClassHelper.VOID_TYPE)*/) {
                    return decapitalize(name.substring(3));
                }
                break;
              case 'i':
                if (name.charAt(1) == 's' && mNode.getParameters().length == 0 && (mNode.getReturnType().equals(ClassHelper.boolean_TYPE) /*|| mNode.getReturnType().equals(ClassHelper.Boolean_TYPE)*/)) {
                    return decapitalize(name.substring(2));
                }
                break;
            }
        }
        return null;
    }

    /**
     * Gets the code for a method (or constructor) as a block.
     * If no code is found, an empty block will be returned.
     * If a single non-block statement is found, a block containing that statement will be returned.
     * Otherwise the existing block statement will be returned.
     * The original {@code node} is not modified.
     *
     * @param mNode the method (or constructor) node
     * @return the found or created block statement
     */
    public static BlockStatement getCodeAsBlock(final MethodNode mNode) {
        Statement code = mNode.getCode();
        BlockStatement block;
        if (code == null) {
            block = new BlockStatement();
        } else if (!(code instanceof BlockStatement)) {
            block = new BlockStatement();
            block.addStatement(code);
        } else {
            block = (BlockStatement) code;
        }
        return block;
    }
}
