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

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import hudson.Extension;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.errors.UnmergedPathException;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
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
    //private boolean isInitialized;

    private transient Map<String, String> backupCommitsMap;
    private transient Git git;

    private static final Logger LOGGER = Logger.getLogger(GitLocation.class.getName());

    @DataBoundConstructor
    public GitLocation(String repositoryURL, boolean enabled) {
        super(enabled);
        this.backupCommitsMap = new HashMap<String, String>();
        this.repositoryURL = repositoryURL;
        //isInitialized = false;
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
     * Verifies if existing repository's initial commit is the same as this GitLocation initialCommit
     *
     * @return
     * @throws NoHeadException
     * @throws IOException
     */
    private boolean verifyRepo() throws NoHeadException, IOException {
        PeriodicBackupLink link = PeriodicBackupLink.get();
        File tempRepoDir = new File(link.getTempDirectory(), "tempRepo");

        LOGGER.info("Trying to verify repository...");
        if (initialCommit != null) {
            try {
                compareInitialCommit(tempRepoDir, initialCommit);
                LOGGER.info("Existing repository was successfully verified.");
                return true;
            } catch (PeriodicBackupException e) {
                LOGGER.info(e.getMessage());
                return false;
            }
        }
        LOGGER.info("Initial commit for this Location is not defined. Cannot verify existing repository.");
        return false;
    }

    /**
     *
     * Sets the value of initialCommit
     *
     * @return true if initialCommit is defined or successfully set
     * @throws NoHeadException
     */
    private boolean defineInitialCommit() throws NoHeadException {
        if (initialCommit != null) {
            return true;
        } else if (git != null && !isRepositoryEmpty()) {
            // Get initial commit
            Iterable<RevCommit> call = git.log().call();

            for (RevCommit revCommit : call) {
                initialCommit = revCommit.name();
            }
            return (initialCommit != null);
        }
        LOGGER.info("Could not define the initial commit.");
        return false;
    }

    /**
     *
     * Initialize git object by either git pull or git clone
     *
     * @return
     * @throws IOException
     * @throws InvalidRefNameException
     * @throws RefNotFoundException
     * @throws RefAlreadyExistsException
     * @throws DetachedHeadException
     * @throws WrongRepositoryStateException
     * @throws InvalidRemoteException
     * @throws InvalidConfigurationException
     * @throws CanceledException
     * @throws PeriodicBackupException
     */
    private boolean initializeGit() throws IOException, InvalidRefNameException, RefNotFoundException,
            RefAlreadyExistsException, DetachedHeadException, WrongRepositoryStateException, InvalidRemoteException,
            InvalidConfigurationException, CanceledException, PeriodicBackupException {

        PeriodicBackupLink link = PeriodicBackupLink.get();
        File tempRepoDir = new File(link.getTempDirectory(), "tempRepo");

        if (git == null) {
            if (new File(tempRepoDir, ".git").exists()) {
                LOGGER.info("Repository directory exists.");

                // Set up the working directory
                git = new Git(new RepositoryBuilder().setWorkTree(tempRepoDir).build());

                if (isRepositoryEmpty()) {
                    LOGGER.info("Repository exists but it's empty.");
                    return true;
                }

                // Checkout master branch
                LOGGER.info("Checking out master.");
                git.checkout().setName("master").call();



                // Setting value for key branch.master.merge in configuration
                git.getRepository().getConfig().setString("branch", "master", "merge", "refs/heads/master");

                // Pull from the origin
                git.pull().call();      // TODO when the tempRepo has content but the origin is empty bare repo we have JGitInternalException: Could not get advertised Ref for branch refs/heads/master

                LOGGER.info("Pull successful.");
                return true;
            } else {
                // Clone the repository
                LOGGER.info("Cloning the repository from " + repositoryURL);
                git = cloneRepo(repositoryURL, tempRepoDir);

                if (isRepositoryEmpty()) {
                    LOGGER.info("Repository exists but it's empty.");
                    return true;
                }

                // Checkout master branch
                git.checkout().setName("master").call();

            }
        }
        return true;

    }

/*
    *//**
     *
     * Initialize GitStorage by cloning/pulling repository to tempRepo directory and creating master branch
     * @throws PeriodicBackupException when initial commit is different then expected one
     * @return true if repository is empty
     *//*
    public synchronized boolean initialize() throws PeriodicBackupException, NoHeadException, IOException, InvalidRemoteException, RefNotFoundException, DetachedHeadException, WrongRepositoryStateException, InvalidConfigurationException, CanceledException, InvalidRefNameException, RefAlreadyExistsException {  //TODO is synchronized the best idea ever?







             //TODO throws JGitInternalException: Cannot lock c:\Temp\niematakiegofolderu\tempRepo\.git\index   during restore - but only (?) first time, further on no exception
            //TODO I also had JGitInternalException: Checkout conflict with files: backup.pbobj when run project after rebooting
            //.checkout().setName("refs/heads/master").call();
            //.checkout().setStartPoint("refs/remotes/origin/master").setName("master").call();

            // Set remote to the origin
            // git.fetch().setRemote("origin").setRefSpecs().call();




        }

        else {
            // Clone the repository
            LOGGER.info("Cloning the repository from " + repositoryURL);
            git = cloneRepo(repositoryURL, tempRepoDir);

            if(isRepositoryEmpty()) {
                System.out.println("********** Ending initialize with id " + identifier + " with result = true");   //TODO
                return true;
            }

           // LOGGER.info("Creating master branch...");
            // Create a master branch and switch to it
           // git.branchCreate().setName("master").call();    // Ref HEAD can not be resolved         TODO 16.05 after using official 1.12.1 and clean build I had RefAlreadyExistsException: Ref master already exists
            git.checkout().setName("master").call();

            if(initialCommit == null && !isRepositoryEmpty()) {
                // Get initial commit
                Iterable<RevCommit> call = git.log().call();

                for (RevCommit revCommit : call) {
                    initialCommit = revCommit.name();
                }
            }
        }
        System.out.println("********** Ending initialize with id " + identifier + " with result = false");   //TODO
        return false;

        //isInitialized = true;
    }*/

    /**
     *
     * Compare initialCommit to the name of the oldest commit in the repository located in tempRepoDir
     *
     * @param tempRepoDir
     * @param initialCommit
     * @throws NoHeadException
     * @throws PeriodicBackupException
     * @throws IOException
     * @return if commits are different returns false
     */
    private boolean compareInitialCommit(File tempRepoDir, String initialCommit) throws NoHeadException, PeriodicBackupException, IOException {
        LOGGER.info("Comparing to the initial commit.");
        // Get commits names
        Iterable<String> logNames = Iterables.transform(git.log().call(), new Function<RevCommit, String>() {
            public String apply(RevCommit revCommit) {
                return revCommit.name();
            }
        });
        if(!Iterables.contains(logNames, initialCommit)) {
            LOGGER.info("Existing repository does not match expected initial commit.");
            return false;
        }
        return true;
    }

    private boolean isRepositoryEmpty() {
        return git.getRepository().getAllRefs().isEmpty();
    }

    @Override
    public Iterable<BackupObject> getAvailableBackups() {



        if(backupCommitsMap == null) {
            backupCommitsMap = new HashMap<String, String>();
        }

        //Check if repository is fine
        try {
            if(!defineInitialCommit()) {
                // TODO what to do...?
            }
            if(!verifyRepo()) {
                try {
                    initializeGit();
                } catch (InvalidRefNameException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                } catch (RefNotFoundException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                } catch (RefAlreadyExistsException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                } catch (DetachedHeadException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                } catch (WrongRepositoryStateException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                } catch (InvalidRemoteException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                } catch (InvalidConfigurationException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                } catch (CanceledException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                } catch (PeriodicBackupException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
            }

                // Get the commits from the git log
                Iterable<RevCommit> commits = Lists.newArrayList(git.log().call());
                List<String> backupObjectStrings = Lists.newArrayList();
                fillInBackupCommitsMap(commits, backupObjectStrings);
                return Iterables.transform(backupObjectStrings, BackupObject.getFromString());

        } catch (NoHeadException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        return Lists.newArrayList();
    }

    @Override
    public void storeBackupInLocation(Iterable<File> archives, File backupObjectFile) throws IOException {


        PeriodicBackupLink link = PeriodicBackupLink.get();
        File tempRepoDir = new File(link.getTempDirectory(), "tempRepo");

        try {
            // Cleaning up working directory
            cleanUpWorkingDirectory();

            // Copy the files
            copyFilesToRepo(archives, backupObjectFile, tempRepoDir);

            Status status = git.status().call();

            if(status.getAdded().size() != 0) {         //TODO get rid of it when you are sure it's safe
                for(String s : status.getAdded()) {
                    System.out.println("**********----------************ Whooooohaa! We have something added: " + s);
                }
            }
            if(status.getChanged().size() != 0) {         //TODO get rid of it when you are sure it's safe
                for(String s : status.getChanged()) {
                    System.out.println("**********----------************ Whooooohaa! We have something changed: " + s);
                }
            }
            Preconditions.checkState(status.getAdded().size() == 0);     // TODO was throwing before exception
            Preconditions.checkState(status.getChanged().size() == 0);  // TODO thrown IllegalStateException 13-05-2011 13:12 when trying to backup
            Preconditions.checkState(status.getRemoved().size() == 0);

            // Call git rm
            for(String file : status.getMissing()) {
                RmCommand rm = git.rm();
                LOGGER.info("Calling git rm on " + file);
                rm.addFilepattern(file).call();
            }

            // Call git add
            for(String file : Sets.union(status.getModified(), status.getUntracked())) {
                AddCommand addCommand = git.add();
                LOGGER.info("Adding " + file + " to the repository...");
                addCommand.addFilepattern(file).call();
            }
        } catch (NoFilepatternException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        // Create commit message
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy MM dd HH:mm");
        String message = "Backup created " + dateFormat.format(new Date());
        // Commit changes
        commitChanges(message);
        // Push changes
        pushChanges();
    }

    private boolean fillInBackupCommitsMap(Iterable<RevCommit> commits, List<String> backupObjectStrings) {
        int commitNr = 0;
        PeriodicBackupLink link = PeriodicBackupLink.get();
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
                if (treeWalk != null) {
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
        return (!backupCommitsMap.isEmpty());
    }

    /**
     *
     * Deletes existing directory with temporary repository    TODO when it cannot delete file from the repository directory it will loop itself for ever
     */
    private synchronized void deleteExistingRepositoryFiles() {
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
     * Deletes files and directories in the working directory, except the ".git" directory
     */
    private void cleanUpWorkingDirectory() throws NoFilepatternException, IOException {
        LOGGER.info("Cleaning up the working directory ...");

        File workDir = git.getRepository().getWorkTree();
        for (File f : workDir.listFiles()) {
            if(! f.getName().equals(".git")) {
                if(f.isDirectory()) {
                    FileUtils.deleteDirectory(f);
                }
                else {
                    if(!f.delete()) {
                        LOGGER.warning("Could not clean up working directory, file " + f.getAbsolutePath() + " could not be deleted.");
                    }
                }
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
    private Git cloneRepo(String urlOfRepo, File destinationDir) throws PeriodicBackupException {
        if((new File(destinationDir, ".git").exists())) {
            throw new PeriodicBackupException("Repository already exists in " + destinationDir);
        }
        CloneCommand cloneCommand = Git.cloneRepository();
        cloneCommand.setDirectory(destinationDir);
        cloneCommand.setURI("file://" + urlOfRepo);
        return cloneCommand.call();
    }

    /**
     *                    //TODO doesn't it assume that the files are 'extracted' archives  ?
     * Copy the backup files to the temporary repository directory
     *
     * @param archives backup files
     * @param backupObjectFile
     * @param tempRepoDir
     * @throws IOException
     */
    private void copyFilesToRepo(Iterable<File> archives, File backupObjectFile, File tempRepoDir) throws IOException {
        LOGGER.info("Copying backup files to the temporary repository directory " + tempRepoDir.getAbsolutePath());
        for(File archive : archives) {
            if(archive.isDirectory()) {
                FileUtils.copyDirectory(archive, new File(tempRepoDir, "archive"));
            }
            else {
                FileUtils.copyFile(archive, new File(tempRepoDir, "archive"));
            }
        }
        FileUtils.copyFile(backupObjectFile, new File(tempRepoDir, "backup.pbobj"));
    }

    /**
     *
     * Commit the changes
     *
     * @param commitMessage message of the commit
     */
    private void commitChanges(String commitMessage) {
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
    private void pushChanges() {
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
