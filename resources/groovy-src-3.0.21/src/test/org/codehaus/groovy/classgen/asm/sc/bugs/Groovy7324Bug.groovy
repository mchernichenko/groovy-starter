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
package org.codehaus.groovy.classgen.asm.sc.bugs

import groovy.transform.stc.StaticTypeCheckingTestCase
import org.codehaus.groovy.classgen.asm.sc.StaticCompilationTestSupport

final class Groovy7324Bug extends StaticTypeCheckingTestCase implements StaticCompilationTestSupport {

    void testInferenceOfListDotOperator() {
        assertScript '''
            class Account {
                String id
            }

            class GCAccount {
                List<Account> sfAccounts
            }

            class User {
                List<GCAccount> gcAccounts
            }

            void foo() {
                def accounts = (1..10).collect { new Account(id: "Id $it") }
                def user1 = new User(gcAccounts: [new GCAccount(sfAccounts: accounts[0..2]), new GCAccount(sfAccounts: accounts[3..4])])
                def user2 = new User(gcAccounts: [new GCAccount(sfAccounts: accounts[5..7]), new GCAccount(sfAccounts: accounts[8..9])])
                def users = [user1,user2]
                def ids = users.gcAccounts.sfAccounts.id.flatten()
                println ids
            }

            foo()
        '''
    }

    void testInferenceOfSpreadDotOperator() {
        assertScript '''
            class Account {
                String id
            }

            class GCAccount {
                List<Account> sfAccounts
            }

            class User {
                List<GCAccount> gcAccounts
            }

            void foo() {
                def accounts = (1..10).collect { new Account(id: "Id $it") }
                def user = new User(gcAccounts: [new GCAccount(sfAccounts: accounts[0..2]), new GCAccount(sfAccounts: accounts[3..4])])
                def ids = user.gcAccounts*.sfAccounts*.id.flatten()
                println ids
            }

            foo()
        '''
    }
}
