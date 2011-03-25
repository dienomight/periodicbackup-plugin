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

import hudson.Extension;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;


/**
 *
 * Use remote git location as backup storage location
 */
public class GitLocation extends Location {

    private String repositoryURL;

    @DataBoundConstructor
    public GitLocation(String repositoryURL, boolean enabled) {
        super(enabled);
        this.repositoryURL = repositoryURL;
    }

    public String getRepositoryURL() {
        return repositoryURL;
    }

    public void setRepositoryURL(String repositoryURL) {
        this.repositoryURL = repositoryURL;
    }

    @Override
    public Iterable<BackupObject> getAvailableBackups() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void storeBackupInLocation(Iterable<File> archives, File backupObjectFile) throws IOException {

        File repoDir = new File("c:\\Temp\\testRepo");
        Git git;
        if(!(new File("c:\\Temp\\testRepo\\.git\\").exists())) {
            git = Git.init().setDirectory(repoDir).call();
        }
        else {
            RepositoryBuilder builder = new RepositoryBuilder();
            builder.setWorkTree(repoDir);
            Repository repository = builder.build();
            git = new Git(repository);
        }

        File plik = new File(repoDir, "chuj.txt");
        plik.createNewFile();
        try {
            git.add().addFilepattern(".").call();
        } catch (NoFilepatternException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }

        try {
            git.commit()
            .setAll(true)
            .setMessage("besouro automatic message")
            .setCommitter("somename", "someemail")
            .call();
        } catch (NoHeadException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (NoMessageException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (ConcurrentRefUpdateException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (WrongRepositoryStateException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }


        LogCommand log = git.log();
        try {
            for (RevCommit commit : log.call()) {
                System.out.println(commit.getAuthorIdent().getName());
                System.out.println(commit.getAuthorIdent().getWhen());
                System.out.println(commit.getFullMessage());
                RevTree tree = commit.getTree();
                System.out.println(tree.getName());

            }
        } catch (NoHeadException e) {
            System.out.println("Dude, you have no HEAD! ;)");
        }


/*        RepositoryBuilder builder = new RepositoryBuilder();
Repository repository = builder.setGitDir(new File("c:\\Temp\\testRepo"))
.readEnvironment() // scan environment GIT_* variables
.findGitDir() // scan up the file system tree
.build();
//repository.create(false);    //IllegalStateException: Repository already exists: c:\Temp\testRepo
System.out.println("-------------------" + repository.getBranch());*/


        // ObjectId head = repository.resolve("HEAD"); // NULL
        //Ref HEAD = repository.getRef("refs/heads/master"); // NULL

        /*RevWalk walk = new RevWalk(repository); // NullPointerException
        RevCommit revCommit = walk.parseCommit(HEAD.getObjectId());
        RevTree tree = walk.parseTree(HEAD.getObjectId());*/
/*
        repository.close();

        Git git = new Git(repository);
        AddCommand add = git.add();
        try {
            add.addFilepattern("someDirectory").call();
        } catch (NoFilepatternException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        CommitCommand commit = git.commit().setMessage("aqq");
        try {
            commit.setMessage("initial commit").call();
        } catch (NoHeadException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (NoMessageException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (ConcurrentRefUpdateException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (WrongRepositoryStateException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        try {
            LogCommand log = (LogCommand) git.log().call();
        } catch (NoHeadException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }*/

/*
        //repository.create(false);
        if(repository.isBare()) System.out.println("The repository is a bare repository, why? I dont fucking know");
        System.out.println("we go on...");
        Git git = new Git(repository);
        git.branchList().call();
        try {
            CreateBranchCommand createBranchCommand = git.branchCreate();
            createBranchCommand.setName("master").call();
        } catch (RefAlreadyExistsException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (RefNotFoundException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (InvalidRefNameException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        AddCommand add = git.add();
        for(File archive : archives) {

            try {
                System.out.println("Adding "  + archive.getAbsolutePath());
                add.addFilepattern(archive.getAbsolutePath()).call();
            } catch (NoFilepatternException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
        }
        System.out.println("comiting");//TODO
        git.commit();
        System.out.println("pushing");//TODO
        git.push();    */
    }

    @Override
    public Iterable<File> retrieveBackupFromLocation(BackupObject backup, File tempDir) throws IOException, PeriodicBackupException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void deleteBackupFiles(BackupObject backupObject) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    public String getDisplayName() {
        return "GIT Location: " + repositoryURL;
    }

    @SuppressWarnings("unused")
    @Extension
    public static class DescriptorImpl extends LocationDescriptor {
        public String getDisplayName() {
            return "GIT";
        }
    }


}
