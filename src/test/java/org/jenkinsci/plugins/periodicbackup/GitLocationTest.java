package org.jenkinsci.plugins.periodicbackup;

import com.google.common.io.Resources;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;
import org.jvnet.hudson.test.HudsonTestCase;

import java.io.File;
import java.io.IOException;

/**
 * Created by IntelliJ IDEA.
 * User: tblaszcz
 * Date: 29-03-11
 */
public class GitLocationTest extends HudsonTestCase {
    @Test
    public void testCloneRepo() throws Exception {
        File destinationRepo = new File(Resources.getResource("data/destinationRepo").getFile());
        File bareRepo = new File(Resources.getResource("data/bareRepo").getFile());

        GitLocation gitLocation = new GitLocation(bareRepo.getAbsolutePath(), false);

        if(destinationRepo.exists()) {
            FileUtils.deleteDirectory(destinationRepo);
        }

        gitLocation.cloneRepo(bareRepo.getAbsolutePath(), destinationRepo);
        assertTrue(new File(destinationRepo, ".git").exists());
    }

}
