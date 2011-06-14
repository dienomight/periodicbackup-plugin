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

    private enum RepoStatus {
        VERIFICATION_FAILED, VERIFIED, NO_COMMIT, COMMIT, UNKNOWN
    }

    private transient RepoStatus status;
    private transient Map<String, String> backupCommitsMap;
    private transient Git git;

    private static final Logger LOGGER = Logger.getLogger(GitLocation.class.getName());

    @DataBoundConstructor
    public GitLocation(String repositoryURL, boolean enabled) {
        super(enabled);
        this.backupCommitsMap = new HashMap<String, String>();
        this.repositoryURL = repositoryURL;
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
     * Sets the status of the repo
     *
     * @return true if verification successful
     * @throws NoHeadException when no reference to the HEAD
     * @throws IOException IO Error
     */
    private boolean verifyRepo() throws NoHeadException, IOException {
        if(status == null) {
            status = RepoStatus.UNKNOWN;
        }

        LOGGER.info("Trying to verify repository...");
        if (initialCommit != null || defineInitialCommit()) {
            try {
                compareInitialCommit(initialCommit);
                LOGGER.info("Existing repository was successfully verified.");
                status = RepoStatus.VERIFIED;
                return true;
            } catch (PeriodicBackupException e) {
                LOGGER.info(e.getMessage());
                status = RepoStatus.VERIFICATION_FAILED;
                return false;
            } catch (RefAlreadyExistsException e) {
                LOGGER.warning("Error during verification of the repository. " + e.getMessage());
            } catch (DetachedHeadException e) {
                LOGGER.warning("Error during verification of the repository. " + e.getMessage());
            } catch (InvalidRemoteException e) {
                LOGGER.warning("Error during verification of the repository. " + e.getMessage());
            } catch (WrongRepositoryStateException e) {
                LOGGER.warning("Error during verification of the repository. " + e.getMessage());
            } catch (InvalidConfigurationException e) {
                LOGGER.warning("Error during verification of the repository. " + e.getMessage());
            } catch (InvalidRefNameException e) {
                LOGGER.warning("Error during verification of the repository. " + e.getMessage());
            } catch (CanceledException e) {
                LOGGER.warning("Error during verification of the repository. " + e.getMessage());
            } catch (RefNotFoundException e) {
                LOGGER.warning("Error during verification of the repository. " + e.getMessage());
            }
        }
        LOGGER.info("Initial commit for this Location is not defined. Cannot verify existing repository.");
        status = GitLocation.RepoStatus.UNKNOWN;
        return false;
    }

    /**
     *
     * Sets the value of initialCommit
     *
     * @return true if initialCommit is defined or successfully set
     * @throws NoHeadException when no reference to the HEAD
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
     * @return true when git object is successfully initialized
     * @throws IOException IO Error
     * @throws InvalidRefNameException when invalid Ref name was encountered
     * @throws RefNotFoundException when a Ref can not be resolved
     * @throws RefAlreadyExistsException when trying to create a Ref with the same name as an exsiting one
     * @throws DetachedHeadException when a command expected a non-detached HEAD reference
     * @throws WrongRepositoryStateException when the state of the repository doesn't allow the execution of a certain command.
     * @throws InvalidRemoteException  when a fetch command was called with an invalid remote
     * @throws InvalidConfigurationException  when a command fails due to an invalid configuration
     * @throws CanceledException  when an operation was canceled
     * @throws PeriodicBackupException when problem with cloning the repository occurs
     */
    private boolean initializeGit() throws IOException, InvalidRefNameException, RefNotFoundException,
            RefAlreadyExistsException, DetachedHeadException, WrongRepositoryStateException, InvalidRemoteException,
            InvalidConfigurationException, CanceledException, PeriodicBackupException {

        LOGGER.info("Initializing git object");

        PeriodicBackupLink link = PeriodicBackupLink.get();
        File tempRepoDir = new File(link.getTempDirectory(), "tempRepo");

        if (git == null) {
            if (new File(tempRepoDir, ".git").exists() && status == RepoStatus.VERIFIED) {
                LOGGER.info("Repository directory exists.");

                // Set up the working directory
                git = new Git(new RepositoryBuilder().setWorkTree(tempRepoDir).build());

                if (isRepositoryEmpty()) {
                    LOGGER.info("Repository exists but it's empty.");
                    status = RepoStatus.NO_COMMIT;
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
                status = RepoStatus.COMMIT;
                return true;
            } else {
                // Clone the repository
                LOGGER.info("Cloning the repository from " + repositoryURL);
                git = cloneRepo(repositoryURL, tempRepoDir);

                if (isRepositoryEmpty()) {
                    LOGGER.info("Repository exists but it's empty.");
                    status = RepoStatus.NO_COMMIT;
                    return true;
                }

                // Checkout master branch
                git.checkout().setName("master").call();

            }
        }
        return true;

    }

    /**
     *
     * Compare initialCommit to the name of the oldest commit in the repository located in tempRepoDir
     *
     * @param initialCommit the String with a initial repository commit SHA-1 id
     * @return if commits are different returns false
     * @throws IOException IO Error
     * @throws InvalidRefNameException when invalid Ref name was encountered
     * @throws RefNotFoundException when a Ref can not be resolved
     * @throws RefAlreadyExistsException when trying to create a Ref with the same name as an exsiting one
     * @throws DetachedHeadException when a command expected a non-detached HEAD reference
     * @throws WrongRepositoryStateException when the state of the repository doesn't allow the execution of a certain command.
     * @throws InvalidRemoteException  when a fetch command was called with an invalid remote
     * @throws InvalidConfigurationException  when a command fails due to an invalid configuration
     * @throws CanceledException  when an operation was canceled
     * @throws NoHeadException when a command expected the HEAD reference to exist but couldn't find such a reference
     * @throws PeriodicBackupException when problem with initializing git object occurs
     */
    private boolean compareInitialCommit(String initialCommit) throws NoHeadException, IOException, RefNotFoundException, InvalidRefNameException, WrongRepositoryStateException, DetachedHeadException, InvalidRemoteException, InvalidConfigurationException, RefAlreadyExistsException, CanceledException, PeriodicBackupException {
        LOGGER.info("Comparing to the initial commit.");
        // Get commits names
        if(git == null) {
            initializeGit();
        }
        Iterable<String> logNames = Iterables.transform(git.log().call(), new Function<RevCommit, String>() {    //TODO when trying to restore WARNING: Could not retrieve backup from location. null java.lang.NullPointerException
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
    public Iterable<BackupObject> getAvailableBackups() {       //TODO There is a bug - after restoring from one of the commits all the newer commits are not being shown! they are shown again only after saving plugin configuration (or restart)
        if(git == null || ((status == RepoStatus.NO_COMMIT || isRepositoryEmpty()) && initialCommit != null)) {
            try {
                if(initialCommit != null) {
                    status = RepoStatus.UNKNOWN;
                }
                initializeGit();
            } catch (IOException e) {
                LOGGER.warning("Error during git initialisation. " + e.getMessage());
            } catch (InvalidRefNameException e) {
                LOGGER.warning("Error during git initialisation. " + e.getMessage());
            } catch (RefNotFoundException e) {
                LOGGER.warning("Error during git initialisation. " + e.getMessage());
            } catch (RefAlreadyExistsException e) {
                LOGGER.warning("Error during git initialisation. " + e.getMessage());
            } catch (DetachedHeadException e) {
                LOGGER.warning("Error during git initialisation. " + e.getMessage());
            } catch (WrongRepositoryStateException e) {
                LOGGER.warning("Error during git initialisation. " + e.getMessage());
            } catch (InvalidRemoteException e) {
                LOGGER.warning("Error during git initialisation. " + e.getMessage());
            } catch (InvalidConfigurationException e) {
                LOGGER.warning("Error during git initialisation. " + e.getMessage());
            } catch (CanceledException e) {
                LOGGER.warning("Error during git initialisation. " + e.getMessage());
            } catch (PeriodicBackupException e) {
                LOGGER.warning("Error during git initialisation. " + e.getMessage());
            }
        }

        if(status == RepoStatus.NO_COMMIT || isRepositoryEmpty()) {
            LOGGER.info("There are no backups committed in the repository");           //TODO if the repo directory is corrupted (fex. most of files deleted) it will still assume that there are no commits
            return Lists.newArrayList();
        }

        try {
            verifyRepo();
        } catch (NoHeadException e) {
            LOGGER.warning("Error during verifying the repository. " + e.getMessage());
        } catch (IOException e) {
            LOGGER.warning("Error during verifying the repository. " + e.getMessage());
        }

        try {
            // Get the commits from the git log
            Iterable<RevCommit> commits = Lists.newArrayList(git.log().call());  // TODO exception during backup now(empty repo) but backup finished successfully  org.eclipse.jgit.api.errors.NoHeadException: No HEAD exists and no explicit starting revision was specified
            //TODO also exception during restore java.lang.NullPointerException

            // Use the commits to fill in the backupCommitsMap
            List<String> backupObjectStrings = Lists.newArrayList();
            fillInBackupCommitsMap(commits, backupObjectStrings);
            return Iterables.transform(backupObjectStrings, BackupObject.getFromString());

        } catch (NoHeadException e) {
            LOGGER.warning("Error during getting the commits. " + e.getMessage());
        } catch (PeriodicBackupException e) {
            LOGGER.warning("Error during getting the commits. " + e.getMessage());
        } catch (IOException e) {
            LOGGER.warning("Error during getting the commits. " + e.getMessage());
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
            LOGGER.warning("Error during preparing to push. " + e.getMessage());
        } catch (PeriodicBackupException e) {
            LOGGER.warning("Error during preparing to push. " + e.getMessage());
        } catch (InvalidRefNameException e) {
            LOGGER.warning("Error during preparing to push. " + e.getMessage());
        } catch (RefAlreadyExistsException e) {
            LOGGER.warning("Error during preparing to push. " + e.getMessage());
        } catch (DetachedHeadException e) {
            LOGGER.warning("Error during preparing to push. " + e.getMessage());
        } catch (InvalidConfigurationException e) {
            LOGGER.warning("Error during preparing to push. " + e.getMessage());
        } catch (InvalidRemoteException e) {
            LOGGER.warning("Error during preparing to push. " + e.getMessage());
        } catch (CanceledException e) {
            LOGGER.warning("Error during preparing to push. " + e.getMessage());
        } catch (WrongRepositoryStateException e) {
            LOGGER.warning("Error during preparing to push. " + e.getMessage());
        } catch (RefNotFoundException e) {
            LOGGER.warning("Error during preparing to push. " + e.getMessage());
        }
        // Create commit message
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy MM dd HH:mm");
        String message = "Backup created " + dateFormat.format(new Date());
        // Commit changes
        commitChanges(message);
        // Push changes
        pushChanges();
    }

    private boolean fillInBackupCommitsMap(Iterable<RevCommit> commits, List<String> backupObjectStrings) throws PeriodicBackupException, IOException {
        if (backupCommitsMap == null) {
            backupCommitsMap = new HashMap<String, String>();
        }
        int commitNr = 0;
        PeriodicBackupLink link = PeriodicBackupLink.get();
        // Getting BackupObject file from each commit
        for (RevCommit commit : commits) {
            commitNr++;
            RevTree tree = commit.getTree();
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
        }
        return (!backupCommitsMap.isEmpty());
    }

/*    *//**
     *
     * Deletes existing directory with temporary repository    TODO when it cannot delete file from the repository directory it will loop itself for ever
     *//*
    private void deleteExistingRepositoryFiles() {
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
                status = RepoStatus.INACCESSIBLE;
                LOGGER.warning("Could not delete the repository directory! " + e.getMessage());
            }
        }
    }*/

    /**
     *
     * Deletes files and directories in the working directory, except the ".git" directory
     * @throws IOException IO Error
     * @throws InvalidRefNameException when invalid Ref name was encountered
     * @throws RefNotFoundException when a Ref can not be resolved
     * @throws RefAlreadyExistsException when trying to create a Ref with the same name as an exsiting one
     * @throws DetachedHeadException when a command expected a non-detached HEAD reference
     * @throws WrongRepositoryStateException when the state of the repository doesn't allow the execution of a certain command.
     * @throws InvalidRemoteException  when a fetch command was called with an invalid remote
     * @throws InvalidConfigurationException  when a command fails due to an invalid configuration
     * @throws CanceledException  when an operation was canceled
     * @throws NoFilepatternException when the options given to a command don't include a file pattern which is mandatory for processing.
     * @throws PeriodicBackupException when problem with initialization of the git object occurs
     */
    private void cleanUpWorkingDirectory() throws NoFilepatternException, IOException, RefNotFoundException, InvalidRefNameException,
            WrongRepositoryStateException, DetachedHeadException, InvalidRemoteException,
            InvalidConfigurationException, RefAlreadyExistsException, CanceledException, PeriodicBackupException {
        LOGGER.info("Cleaning up the working directory ...");

        if(git==null) {
            initializeGit();
        }
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
     * Clone repository in the temporary directory, the directory have to be empty
     *
     * @param urlOfRepo URL of the repository to clone
     * @param destinationDir directory where cloned repository will be placed
     * @return Git object of cloned repository
     * @throws PeriodicBackupException if repository exists before cloning
     */
    private Git cloneRepo(String urlOfRepo, File destinationDir) throws PeriodicBackupException {
        /*if((new File(destinationDir, ".git").exists())) {
            throw new PeriodicBackupException("Repository already exists in " + destinationDir);
        } */
        CloneCommand cloneCommand = Git.cloneRepository();
        cloneCommand.setDirectory(destinationDir);
        cloneCommand.setURI("file://" + urlOfRepo);
        return cloneCommand.call();
    }

    /**
     * Copy the backup files to the temporary repository directory
     *
     * @param archives backup archive files
     * @param backupObjectFile BackupObject file corresponding to the archives
     * @param tempRepoDir the directory where temporary repository is located
     * @throws IOException when IO error
     */
    private void copyFilesToRepo(Iterable<File> archives, File backupObjectFile, File tempRepoDir) throws IOException {
        LOGGER.info("Copying backup files to the temporary repository directory " + tempRepoDir.getAbsolutePath());
        for(File archive : archives) {
            File destination = new File(tempRepoDir, "archive");
            if(archive.isDirectory()) {
                FileUtils.copyDirectory(archive, destination);
            }
            else {
                FileUtils.copyFile(archive, new File(destination, archive.getName()));
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
            // get commits and put it into the map
            getAvailableBackups();
        }

        String commit = backupCommitsMap.get(backupObjectAsString);

        try {
            LOGGER.info("Checking out commit " + commit);
            git.checkout().setName(commit).call();
        } catch (RefAlreadyExistsException e) {
            LOGGER.warning("Error during checkout." + e.getMessage());
        } catch (RefNotFoundException e) {
            LOGGER.warning("Error during checkout." + e.getMessage());
        } catch (InvalidRefNameException e) {
            LOGGER.warning("Error during checkout." + e.getMessage());
        }

        File tempRepoDir = new File(tempDir, "tempRepo");
        File archiveDirectory = new File(tempRepoDir, "archive");

        File[] files = archiveDirectory.listFiles();
        if(files == null || files.length < 1) {
            throw new PeriodicBackupException("No backup files found!");      // TODO this is thrown sometimes when trying to restore
        }
        return Lists.newArrayList(files);
    }

    @Override
    public void deleteBackupFiles(BackupObject backupObject) {} // Cannot delete history of git

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
