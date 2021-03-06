package io.gitdetective.tools

/**
 * Homemade admin CLI till I get serious about it
 *
 * @author <a href="mailto:brandon.fergerson@codebrig.com">Brandon Fergerson</a>
 */
class GDTools {

    static void main(String[] args) {
        if (args == null || args.length == 0) {
            System.err.println("Missing command")
            System.exit(-1)
        } else if (args[0].toLowerCase() == "getprojects") {
            GetProjects.main(args.drop(1))
        } else if (args[0].toLowerCase() == "findprojects") {
            FindProjects.main(args.drop(1))
        } else if (args[0].toLowerCase() == "createjob") {
            CreateJob.main(args.drop(1))
        } else if (args[0].toLowerCase() == "createjobs") {
            CreateJobs.main(args.drop(1))
        } else if (args[0].toLowerCase() == "removejobs") {
            RemoveJobs.main(args.drop(1))
        } else if (args[0].toLowerCase() == "movejobs") {
            MoveJobs.main(args.drop(1))
        } else if (args[0].toLowerCase() == "getprojectdefinedfunctions") {
            GetProjectDefinedFunctions.main(args.drop(1))
        } else if (args[0].toLowerCase() == "undefineprojectfunctions") {
            UndefineProjectFunctions.main(args.drop(1))
        } else {
            System.err.println("Unknown command: " + args.toArrayString())
            System.exit(-1)
        }
    }

}
