/*
 * Copyright 2013-2023, Seqera Labs
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
 */

package nextflow.nomad.client

import spock.lang.Specification

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class NomadResponseJsonTest extends Specification {

    def 'should create a response from a map' () {

        given:
        def MAP = [foo: 'one', bar: 'two']

        when:
        def resp = new NomadResponseJson(MAP)
        then:
        resp.foo == 'one'
        resp.bar == 'two'
        resp.toString() == '''
            {
                "foo": "one",
                "bar": "two"
            }
            '''.stripIndent().trim()

    }

    def 'should create a response from a json string' () {

        when:
        def resp = new NomadResponseJson('{"foo":"one","bar":"two"}')
        then:
        resp.foo == 'one'
        resp.bar == 'two'
        resp.toString() == '''
            {
                "foo": "one",
                "bar": "two"
            }
            '''.stripIndent().trim()

    }

    def 'should create a response from an error message' () {
        when:
        def resp = new NomadResponseJson('Ooops .. this crashed')
        then:
        resp.toString() == 'Ooops .. this crashed'
    }

    
}
