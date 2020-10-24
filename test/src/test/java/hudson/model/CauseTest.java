/*
 * The MIT License
 *
 * Copyright 2012 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.model;

import hudson.XmlFile;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.*;

import hudson.util.StreamTaskListener;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

public class CauseTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    @Issue("JENKINS-14814")
    @Test public void deeplyNestedCauses() throws Exception {
        FreeStyleProject a = j.createFreeStyleProject("a");
        FreeStyleProject b = j.createFreeStyleProject("b");
        Run<?,?> early = null;
        Run<?,?> last = null;
        for (int i = 1; i <= 15; i++) {
            last = b.scheduleBuild2(0, new Cause.UpstreamCause((Run<?,?>) a.scheduleBuild2(0, last == null ? null : new Cause.UpstreamCause(last)).get())).get();
            if (i == 5) {
                early = last;
            }
        }
        String buildXml = new XmlFile(Run.XSTREAM, new File(early.getRootDir(), "build.xml")).asString();
        assertTrue("keeps full history:\n" + buildXml, buildXml.contains("<upstreamBuild>1</upstreamBuild>"));
        buildXml = new XmlFile(Run.XSTREAM, new File(last.getRootDir(), "build.xml")).asString();
    int maxUpstreamCauses = 10;
        int count = buildXml.split(Pattern.quote("<hudson.model.Cause_-UpstreamCause")).length;
        assertFalse(count + "is greater than " + maxUpstreamCauses + "; build.xml is too big:\n" + buildXml, count > maxUpstreamCauses);
    }

    @Issue("JENKINS-15747")
    @Test public void broadlyNestedCauses() throws Exception {
        FreeStyleProject a = j.createFreeStyleProject("a");
        FreeStyleProject b = j.createFreeStyleProject("b");
        FreeStyleProject c = j.createFreeStyleProject("c");
        Run<?,?> last = null;
        for (int i = 1; i <= 10; i++) {
            Cause cause = last == null ? null : new Cause.UpstreamCause(last);
            Future<? extends Run<?,?>> next1 = a.scheduleBuild2(0, cause);
            a.scheduleBuild2(0, cause);
            cause = new Cause.UpstreamCause(next1.get());
            Future<? extends Run<?,?>> next2 = b.scheduleBuild2(0, cause);
            b.scheduleBuild2(0, cause);
            cause = new Cause.UpstreamCause(next2.get());
            Future<? extends Run<?,?>> next3 = c.scheduleBuild2(0, cause);
            c.scheduleBuild2(0, cause);
            last = next3.get();
        }
    String buildXml = new XmlFile(Run.XSTREAM, new File(last.getRootDir(), "build.xml")).asString();
    /* The number of upstream causes is one less than the length of the split. */
        int count = buildXml.split(Pattern.quote("<hudson.model.Cause_-UpstreamCause")).length - 1;
        assertFalse("Too many upstream causes: " + count + " in build.xml:\n" + buildXml, count > 25);
        //j.interactiveBreak();
    }

    @Test public void multipleLeafyCauses() throws Exception {
        /* Create a project that is a sink for many which cannot run right away */
        /* Trigger a buid of that project with from many. */
        FreeStyleProject a = j.createFreeStyleProject("a");
        FreeStyleProject b = j.createFreeStyleProject("b");
	List<Cause> causes = new ArrayList<>();

        for (int i = 0; i <= 50; i++) {
            Future<? extends Run<?,?>> next = a.scheduleBuild2(0);
	    causes.add(new Cause.UpstreamCause(next.get()));
	}
	Run<?,?> sink = a.scheduleBuild2(0, new Cause.UpstreamCause(a.scheduleBuild2(0, new CauseAction(causes)).get())).get();
	String buildXml = new XmlFile(Run.XSTREAM, new File(sink.getRootDir(), "build.xml")).asString();
        int count = buildXml.split(Pattern.quote("<hudson.model.Cause_-UpstreamCause")).length - 1;
	assertFalse("leaf builds aren't being truncated, there are " + count + "in:\n" + buildXml, count > 50);
    }

    @Test public void manyMultipleCauses() throws Exception {
       List<FreeStyleProject> projects = new ArrayList<>();
       List<Cause> causes = new ArrayList<>();
       Run<?,?> last = null;
       for (int i = 1; i <= 500; i++) {
           FreeStyleProject p = j.createFreeStyleProject("mmuc" + i);
           last = p.scheduleBuild2(0, new Cause.UserCause()).get();
           causes.add(new Cause.UpstreamCause(last));
       }
       CauseAction ca = new CauseAction(causes);
       assertTrue("Total causes aren't truncated: " + ca.getCauses().size(), ca.getCauses().size() <= 200);
    }


    @Issue("JENKINS-48467")
    @Test public void userIdCausePrintTest() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TaskListener listener = new StreamTaskListener(baos);

        //null userId - print unknown or anonymous
        Cause causeA = new Cause.UserIdCause(null);
        causeA.print(listener);

        assertEquals(baos.toString().trim(),"Started by user unknown or anonymous");
        baos.reset();

        //SYSTEM userid  - getDisplayName() should be SYSTEM
        Cause causeB = new Cause.UserIdCause();
        causeB.print(listener);

        assertThat(baos.toString(), containsString("SYSTEM"));
        baos.reset();

        //unknown userid - print unknown or anonymous
        Cause causeC = new Cause.UserIdCause("abc123");
        causeC.print(listener);

        assertEquals(baos.toString().trim(),"Started by user unknown or anonymous");
        baos.reset();

        //More or less standard operation
        //user userid  - getDisplayName() should be foo
        User user = User.getById("foo", true);
        Cause causeD = new Cause.UserIdCause(user.getId());
        causeD.print(listener);

        assertThat(baos.toString(), containsString(user.getDisplayName()));
        baos.reset();
    }
}
