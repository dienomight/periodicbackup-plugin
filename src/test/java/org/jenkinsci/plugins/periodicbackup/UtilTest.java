package org.jenkinsci.plugins.periodicbackup;

import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import junit.framework.TestCase;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * Author: tblaszcz
 * Date: 19-01-11
 */
public class UtilTest extends TestCase {
    @Test
    public void testGetRelativePath() throws Exception {
        File file = new File(Resources.getResource("data/temp/dummy").getFile());
        File baseDirectory = new File(Resources.getResource("data/").getFile());

        String result = Util.getRelativePath(file, baseDirectory);
        String expectedResult = "temp/dummy";

        assertTrue(result.equals(expectedResult));
    }

    @Test
    public void testGenerateFileName() throws Exception {
        String filenameBase = Util.generateFileNameBase(new Date());

        assertTrue(filenameBase.length() > 0);
    }

    @Test
    public void testCreateFileName() throws Exception {
        String filenameBase = "backup";
        String extension = "pbobj";
        String filename = Util.createFileName(filenameBase, extension);

        assertTrue(filename.length() == (filenameBase.length() + extension.length() + 1));
    }

    @Test
    public void testCreateBackupObjectFile() throws Exception {
        File tempDirectory = new File(Resources.getResource("data/temp").getFile());
        BackupObject backupObject = new BackupObject(new FullBackup(), new ZipStorage(false, 0), new LocalDirectory(tempDirectory, true), new Date());
        String fileNameBase = "backupfile";

        File result = Util.createBackupObjectFile(backupObject, tempDirectory.getAbsolutePath(), fileNameBase);
        String expectedFileName = fileNameBase + "." + BackupObject.EXTENSION;

        assertTrue(result.exists());
        assertEquals(result.getName(), expectedFileName);
    }

    @Test
    public void testIsValidBackupObjectFile() throws Exception {
        File backupObjectFile = new File(Resources.getResource("data/test.pbobj").getFile());
        File notBackupObjectFile = new File(Resources.getResource("data/archive1").getFile());
        assertFalse(Util.isValidBackupObjectFile(notBackupObjectFile));
        assertTrue(Util.isValidBackupObjectFile(backupObjectFile));
    }

    @Test //TODO
    public void testSomething() throws IOException, NoHeadException, RefNotFoundException, DetachedHeadException, WrongRepositoryStateException, InvalidRemoteException, InvalidConfigurationException, CanceledException, InvalidRefNameException, RefAlreadyExistsException, URISyntaxException {
        File tempRepoDir = new File("c:\\Temp\\niematakiegofolderu\\tempRepo\\");
        Git sourceGit = new Git(new RepositoryBuilder().setWorkTree(tempRepoDir).build());

        sourceGit.checkout().setName("refs/heads/master").call();
        // Setting value for key branch.master.merge in configuration
        sourceGit.getRepository().getConfig().setString("branch", "master", "merge", "refs/heads/master");

        //sourceGit.getRepository().updateRef("master", false);        // tego chyba nie trzeba
        //FetchResult fetchResult = sourceGit.fetch().setRemote("origin").setRefSpecs().call(); //poki co dziala bez tego

        sourceGit.pull().call();

        LogCommand log = sourceGit.log();
        Iterable<RevCommit> call = log.call();
        List<RevCommit> list = Lists.newArrayList(call);
        for(RevCommit rc : list) {
            System.out.println(rc.getShortMessage() + rc.name());
        }
        System.out.println("first commit is: " + list.get(list.size() - 1).name());
        //sourceGit.checkout().setName("37face4273b462fa907b9390c26b64a8bb924ce0").call();
    }

    @Test //TODO
    public void testSomething2() throws IOException, InvalidRefNameException, RefNotFoundException, RefAlreadyExistsException, DetachedHeadException, WrongRepositoryStateException, InvalidRemoteException, InvalidConfigurationException, CanceledException {

        File tempRepoDir = new File("c:\\Temp\\niematakiegofolderu\\tempRepo\\");
        Git sourceGit = new Git(new RepositoryBuilder().setWorkTree(tempRepoDir).build());
        sourceGit.checkout().setName("refs/heads/master").call();
        sourceGit.getRepository().getConfig().setString("branch", "master", "merge", "refs/heads/master");
        sourceGit.pull().call();
        LogCommand log = sourceGit.log();
        Iterable<RevCommit> call = null;
        try {
            call = log.call();
        } catch (NoHeadException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        List<RevCommit> commits = Lists.newArrayList(call);

        for (RevCommit commit : commits) {
            RevTree tree = commit.getTree();
            TreeWalk treeWalk = TreeWalk.forPath(sourceGit.getRepository(), "backup.pbobj", tree);
            if (treeWalk != null) {
                byte[] bArray = treeWalk.getObjectReader().open(treeWalk.getObjectId(0)).getBytes();
                String stringFromBytes = new String(bArray);
                System.out.println(stringFromBytes);
            }
        }
    }

    @Test
    public void testSomething3() {
        Map<String, String> backupCommitsMap = new Hashtable<String, String>();
        backupCommitsMap.put("a", "b");
        System.out.println(backupCommitsMap.get("a"));

        int[] A = {-7, 1, 5, 2, -4, 3, 0};
        int N = A.length;

        int leftSum, rightSum;
        int i, j, k;

        for (i = 0; i < N; i++) {
            leftSum = 0;
            rightSum = 0;

            for (j = 0; j < i; j++) {
                leftSum += A[j];
            }
            for (k = i + 1; k < N; k++) {
                leftSum += A[k];
            }
            if (leftSum == rightSum) {
                System.out.println("jest i :" + i);
            }
        }
        System.out.println(-1);
    }


}
