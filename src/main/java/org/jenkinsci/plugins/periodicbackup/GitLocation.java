/*
 * The MIT License
 *
 * Copyright (c) 2010 - 2011, Tomasz Blaszczynski, Emanuele Zattin
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

package org.jenkinsci.plugins.periodicbackup;

import com.google.common.base.Objects;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import hudson.Extension;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.errors.UnmergedPathException;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.storage.file.FileRepository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;

/**
 *
 * Use remote git location as the backup storage location
 */
public class GitLocation extends Location {

    private String repositoryURL;
    private String initialCommit;
    private boolean isInitialized;
    private Map<String, String> backupCommitsMap;

    private transient Git git;

    private static final Logger LOGGER = Logger.getLogger(GitLocation.class.getName());

    @DataBoundConstructor
    public GitLocation(String repositoryURL, boolean enabled) {
        super(enabled);
        this.backupCommitsMap = new HashMap<String, String>();
        this.repositoryURL = repositoryURL;
        isInitialized = false;
    }

    @SuppressWarnings("unused")
    public String getRepositoryURL() {
        return repositoryURL;
    }

    @SuppressWarnings("unused")
    public void setRepositoryURL(String repositoryURL) {
        this.repositoryURL = repositoryURL;
    }

    /**
     *
     * Initialize GitStorage by cloning/pulling repository to tempRepo directory and creating master branch
     * @throws PeriodicBackupException when initial commit is different then expected one
     */
    public void initialize() throws PeriodicBackupException {
        PeriodicBackupLink link = PeriodicBackupLink.get();
        File tempRepoDir = new File(link.getTempDirectory(), "tempRepo");
        if(backupCommitsMap == null) {
            backupCommitsMap = new HashMap<String, String>();
        }

        //TODO: how to check if it is the right/non-corrupted repository?     commit as variable
        if (new File(tempRepoDir, ".git").exists()) {
            LOGGER.info("Repository directory exists, trying to pull");
            if(initialCommit != null) {
                LOGGER.info("Comparing to the initial commit.");
                // Get initial commit
                LogCommand log = git.log();
                Iterable<RevCommit> call = null;
                try {
                    call = log.call();
                } catch (NoHeadException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
                List<RevCommit> list = Lists.newArrayList(call);
                boolean hasInitial = false;
                for(RevCommit revCommit : list) {
                    System.out.println(revCommit.name());//TODO
                    if(revCommit.name().equals(initialCommit)) {
                        hasInitial = true;
                    }
                }
                if(!hasInitial) {
                    throw new PeriodicBackupException("Existing repository does not match expected initial commit.");
                }

            }
            FileRepository fileRepository = null;
            try {
                fileRepository = new FileRepository(tempRepoDir);
            } catch (IOException e) {
                LOGGER.warning("There was a problem with repository. " + e.getMessage());
            }
            if (fileRepository != null) {
                // Set up the working directory
                try {
                    git = new Git(new RepositoryBuilder().setWorkTree(tempRepoDir).build());
                } catch (IOException e) {
                    LOGGER.warning("Could not set work tree to " + tempRepoDir.getAbsolutePath() + " " + e.getMessage());
                }
                // Checkout master branch
                try {
                    git.checkout().setStartPoint("refs/remotes/origin/master").setName("master").call();
                } catch (RefAlreadyExistsException e) {
                    LOGGER.warning("Problem during checkout " + e.getMessage());
                } catch (RefNotFoundException e) {
                    LOGGER.warning("Problem during checkout " + e.getMessage());
                } catch (InvalidRefNameException e) {
                    LOGGER.warning("Problem during checkout " + e.getMessage());
                }
            }
            // Set remote to the origin
            try {
                git.fetch().setRemote("origin").setRefSpecs().call();
            } catch (InvalidRemoteException e) {
                LOGGER.warning("Problem during fetch. " + e.getMessage());
            }
            // Setting value for key branch.master.merge in configuration
            git.getRepository().getConfig().setString("branch", "master", "merge", "refs/heads/master");
            // Pull from the origin
            try {
                git.pull().call();
            } catch (WrongRepositoryStateException e) {
                LOGGER.warning("Problem during pull. " + e.getMessage());
            } catch (InvalidConfigurationException e) {
                LOGGER.warning("Problem during pull. " + e.getMessage());
            } catch (DetachedHeadException e) {
                LOGGER.warning("Problem during pull. " + e.getMessage());
            } catch (InvalidRemoteException e) {
                LOGGER.warning("Problem during pull. " + e.getMessage());
            } catch (CanceledException e) {
                LOGGER.warning("Problem during pull. " + e.getMessage());
            } catch (RefNotFoundException e) {
                LOGGER.warning("Problem during pull. " + e.getMessage());
            }
        } else {
            // Delete temp repository if it already exists
            cleanUpRepository();
            // Clone the repository
            try {
                git = cloneRepo(repositoryURL, tempRepoDir);
            } catch (PeriodicBackupException e) {
                LOGGER.warning("Could not clone repository! " + e.getMessage());
                return;
            }
            LOGGER.info("Creating master branch...");
            // Create a master branch and switch to it
            try {
                git.branchCreate().setName("master").call();
                git.checkout().setName("master").call();
            } catch (RefAlreadyExistsException e) {
                LOGGER.warning("Could not create master branch " + e.getMessage());
            } catch (RefNotFoundException e) {
                LOGGER.warning("Could not create master branch " + e.getMessage());
            } catch (InvalidRefNameException e) {
                LOGGER.warning("Could not create master branch " + e.getMessage());
            }

            // Get initial commit
            LogCommand log = git.log();
            Iterable<RevCommit> call = null;
            try {
                call = log.call();
            } catch (NoHeadException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
            List<RevCommit> list = Lists.newArrayList(call);
            initialCommit = list.get(list.size() - 1).name();
        }


        isInitialized = true;
    }

    @Override
    public Iterable<BackupObject> getAvailableBackups() {
        if(!isInitialized || git == null) {
            try {
                initialize();
            } catch (PeriodicBackupException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
        }
        if(git  == null) System.out.println("--------------------------wtf? git is null");//TODO
        LogCommand log = git.log();
        Iterable<RevCommit> call = null;
        try {
            call = log.call();
        } catch (NoHeadException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        List<RevCommit> commits = Lists.newArrayList(call);
        List<String> backupObjectStrings = Lists.newArrayList();
        PeriodicBackupLink link = PeriodicBackupLink.get();
        File tempRepoDir = new File(link.getTempDirectory(), "tempRepo");
        int commitNr = 0;

        // Getting BackupObject file from each commit
        for (RevCommit commit : commits) {
            commitNr++;
            RevTree tree = commit.getTree();
            try {
                File pbobjTempFile = new File(link.getTempDirectory(), "temp" + commitNr + ".pbobj");
                if (pbobjTempFile.exists()) {
                    if (!pbobjTempFile.delete()) {
                        throw new PeriodicBackupException("Could not delete file " + pbobjTempFile.getAbsolutePath());
                    }
                    if (!pbobjTempFile.createNewFile()) {
                        throw new PeriodicBackupException("Could not create file " + pbobjTempFile.getAbsolutePath());
                    }
                }
                TreeWalk treeWalk = TreeWalk.forPath(git.getRepository(), "backup.pbobj", tree);
                if(treeWalk != null) {
                    byte[] bytesArray = treeWalk.getObjectReader().open(treeWalk.getObjectId(0)).getBytes();
                    String stringFromBytes = new String(bytesArray);
                    backupCommitsMap.put(stringFromBytes, commit.name());
                    backupObjectStrings.add(stringFromBytes);
                }

            } catch (IOException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            } catch (PeriodicBackupException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }

        }
        // Check out back to master
        try {
            git.checkout().setName("refs/heads/master").call();
        } catch (RefAlreadyExistsException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (RefNotFoundException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (InvalidRefNameException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }

        return Iterables.transform(backupObjectStrings, BackupObject.getFromString());
    }

    @Override
    public void storeBackupInLocation(Iterable<File> archives, File backupObjectFile) throws IOException {
        if (!isInitialized) {
            try {
                initialize();
            } catch (PeriodicBackupException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
        }
        PeriodicBackupLink link = PeriodicBackupLink.get();
        File tempRepoDir = new File(link.getTempDirectory(), "tempRepo");
        // Add files
        addFilesToRepo(archives, backupObjectFile, tempRepoDir);
        // Create commit message
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy MM dd HH:mm");
        String message = "Backup created " + dateFormat.format(new Date());
        // Commit changes
        commitChanges(message);
        // Push changes
        pushChanges();
    }

    /**
     *
     * Deletes existing directory with temporary repository
     */
    public synchronized void cleanUpRepository() {
        if(git != null) {
            git.getRepository().close();
        }
        PeriodicBackupLink link = PeriodicBackupLink.get();
        File tempRepoDir = new File(link.getTempDirectory(), "tempRepo");
        if(tempRepoDir.exists()) {
            LOGGER.info("Temporary git repository already exists. Deleting...");
            try {
                FileUtils.deleteDirectory(tempRepoDir);
            }
            catch (IOException e) {
                LOGGER.warning("Could not delete the repository directory! " + e.getMessage());
            }
        }
    }

    /**
     *
     * Clone repository in the temporary directory
     *
     * @param urlOfRepo URL of the repository to clone
     * @param destinationDir directory where cloned repository will be placed
     * @return Git object of cloned repository
     * @throws PeriodicBackupException if repository exists before cloning
     */
    public Git cloneRepo(String urlOfRepo, File destinationDir) throws PeriodicBackupException {
        if((new File(destinationDir, ".git").exists())) {
            throw new PeriodicBackupException("Repository already exists in " + destinationDir);
        }
        CloneCommand cloneCommand = Git.cloneRepository();
        cloneCommand.setDirectory(destinationDir);
        cloneCommand.setURI("file://" + urlOfRepo);
        return cloneCommand.call();
    }

    /**
     *
     * Copy backup files to the repository directory and adds the files to the git repository
     *
     * @param archives files to backup
     * @param backupObjectFile BackupObject file
     * @param tempRepoDir temporary repository directory
     */
    public void addFilesToRepo(Iterable<File> archives, File backupObjectFile, File tempRepoDir) {
        if(git != null) {
            AddCommand addCommand = git.add();
            try {
                LOGGER.info("Copying backup files to temporary repository directory " + tempRepoDir.getAbsolutePath());
                for(File archive : archives) {
                    if(archive.isDirectory()) {
                        FileUtils.copyDirectory(archive, new File(tempRepoDir, "archive"));
                    }
                    else {
                        FileUtils.copyFile(archive, new File(tempRepoDir, "archive"));
                    }
                }
                FileUtils.copyFile(backupObjectFile, new File(tempRepoDir, "backup.pbobj"));
            } catch (IOException e) {
                LOGGER.warning("Error when copying files to " + tempRepoDir.getAbsolutePath() + " " + e.getMessage());
                return;
            }
            try {
                LOGGER.info("Adding files to the repository...");
                addCommand.addFilepattern(".").call();
            } catch (NoFilepatternException e) {
                LOGGER.warning("Cannot add files. " + e.getMessage());
            }
        }
    }

    /**
     *
     * Commit the changes
     *
     * @param commitMessage message of the commit
     */
    public void commitChanges(String commitMessage) {
        CommitCommand commitCommand = git.commit();
        try {
            LOGGER.info("Committing...");
            commitCommand.setMessage(commitMessage).call();
        } catch (NoHeadException e) {
            LOGGER.warning("Could not commit changes. " + e.getMessage());
        } catch (NoMessageException e) {
            LOGGER.warning("Could not commit changes. " + e.getMessage());
        } catch (UnmergedPathException e) {
            LOGGER.warning("Could not commit changes. " + e.getMessage());
        } catch (ConcurrentRefUpdateException e) {
            LOGGER.warning("Could not commit changes. " + e.getMessage());
        } catch (WrongRepositoryStateException e) {
            LOGGER.warning("Could not commit changes. " + e.getMessage());
        }
    }

    /**
     *
     * Push the changes to the remote
     */
    public void pushChanges() {
        String branch = "refs/heads/master";

        try {
            LOGGER.info("Pushing to the remote " + repositoryURL);
            RefSpec spec = new RefSpec(branch + ":" + branch);
		    git.push().setRemote(repositoryURL).setRefSpecs(spec).call();
        } catch (InvalidRemoteException e) {
            LOGGER.warning("Cannot push to the remote " + e.getMessage());
        }
    }

    @Override
    public Iterable<File> retrieveBackupFromLocation(final BackupObject backup, File tempDir) throws IOException, PeriodicBackupException {

        String backupObjectAsString = backup.getAsString();
        if(backupCommitsMap == null) {
            backupCommitsMap = new HashMap<String, String>();
            getAvailableBackups();
        }

        if(backupCommitsMap  == null) System.out.println("----------------------------- wtf ? map stil null");//TODO
        String commit = backupCommitsMap.get(backupObjectAsString);

        try {
            LOGGER.info("Checking out commit " + commit);
            git.checkout().setName(commit).call();
        } catch (RefAlreadyExistsException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (RefNotFoundException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (InvalidRefNameException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }

        File tempRepoDir = new File(tempDir, "tempRepo");
        File archiveDirectory = new File(tempRepoDir, "archive");

        return Lists.newArrayList(archiveDirectory.listFiles());
    }

    @Override
    public void deleteBackupFiles(BackupObject backupObject) {
        //TODO: should we ignore (we cannot delete history) too old or redundant backups?
    }

    public String getDisplayName() {
        return "GIT Location: " + repositoryURL;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof GitLocation) {
            GitLocation that = (GitLocation) o;
            return Objects.equal(this.repositoryURL, that.repositoryURL)
                    && Objects.equal(this.enabled, that.enabled);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(repositoryURL, enabled);
    }

    @SuppressWarnings("unused")
    @Extension
    public static class DescriptorImpl extends LocationDescriptor {
        public String getDisplayName() {
            return "GIT";
        }
    }

}
