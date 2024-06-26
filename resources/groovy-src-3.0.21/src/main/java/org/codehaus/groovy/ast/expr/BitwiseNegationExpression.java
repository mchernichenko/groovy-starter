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
package org.codehaus.groovy.ast.expr;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GroovyCodeVisitor;

public class BitwiseNegationExpression extends Expression {

    private final Expression expression;

    public BitwiseNegationExpression(Expression expression) {
        this.expression = expression;
    }

    public Expression getExpression() {
        return expression;
    }

    @Override
    public void visit(GroovyCodeVisitor visitor) {
        visitor.visitBitwiseNegationExpression(this);
    }

    @Override
    public Expression transformExpression(ExpressionTransformer transformer) {
        Expression ret = new BitwiseNegationExpression(transformer.transform(expression));
        ret.setSourcePosition(this);
        ret.copyNodeMetaData(this);
        return ret;
    }

    @Override
    public String getText() {
        return "~(" + expression.getText() + ")";
    }

    /**
     * @see org.codehaus.groovy.runtime.InvokerHelper#bitwiseNegate(Object)
     */
    @Override
    public ClassNode getType() {
        ClassNode type = expression.getType();
        if (ClassHelper.STRING_TYPE.equals(type) || ClassHelper.GSTRING_TYPE.equals(type)) {
            type = ClassHelper.PATTERN_TYPE; // GROOVY-10936
        }
        return type;
    }
}
