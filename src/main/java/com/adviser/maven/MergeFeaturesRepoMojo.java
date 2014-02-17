/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adviser.maven;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Add Features necessary bundles into system folder Repo
 * 
 * @goal merge-features
 * @phase compile
 * @execute phase="compile"
 * @requiresDependencyResolution runtime
 * @inheritByDefault true
 * @description Merge features.xml repos in one repository
 */
public class MergeFeaturesRepoMojo extends MojoSupport {

    /**
     * @parameter
     */
    private List<String> descriptors;

    /**
     * @parameter
     */
    private boolean resolveDefinedRepositoriesRecursively = true;

    /**
     * The file to generate
     * 
     * @parameter default-value="${project.build.directory}/generated/features.xml"
     */
    private File outputFile;

    /**
     * @parameter
     */
    private String mergedRepoName;

    /**
     * @parameter
     */
    private List<String> mergedRepoRepositories;

    /**
     * @parameter
     */
    private List<String> includeFeaturesPrefixes;

    
    private Map<String, String> visitedDescriptors = new HashMap<String, String>();


    public void execute() throws MojoExecutionException, MojoFailureException {

        try {
            Set<String> bundles = new HashSet<String>();
            Map<String, Feature> featuresMap = new HashMap<String, Feature>();
            for (String uri : descriptors) {
                retrieveDescriptorsRecursively(uri, bundles, featuresMap);
            }
            writeFeatures(featuresMap);

        } catch (MojoExecutionException e) {
            throw e;
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Error populating repository", e);
        }
    }

    private void writeFeatures(Map<String, Feature> featuresMap) throws MojoExecutionException {
        PrintStream out = null;
        try {
            TreeMap<String, Feature> sortedFeatureMap = new TreeMap<String, Feature>(featuresMap);

            File parent = outputFile.getParentFile();
            if (!parent.exists()) {
                parent.mkdirs();
            }
            out = new PrintStream(new FileOutputStream(outputFile));
            getLog().info("Generating " + outputFile.getAbsolutePath());

            String name = "";
            if (mergedRepoName != null) {
                name = " name=\"" + mergedRepoName + "\"";
            }
            out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            out.println("<features " + name);
            out.println("\txmlns=\"http://karaf.apache.org/xmlns/features/v1.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
            out.println("\txsi:schemaLocation=\"http://karaf.apache.org/xmlns/features/v1.0.0 http://karaf.apache.org/xmlns/features/v1.0.0\">");
            if (mergedRepoRepositories != null) {
                out.println("");
                for (String repo : mergedRepoRepositories) {
                    out.println("\t<repository>" + repo + "</repository>");
                }
            }
            for (Map.Entry<String, Feature> entry : sortedFeatureMap.entrySet()) {
                Feature feature = entry.getValue();
                if (includeFeaturesPrefixes != null) {
                    for (String prefix : includeFeaturesPrefixes) {
                        if (feature.getName().startsWith(prefix)) {
                            getLog().info(" Generating feature " + feature.getName() + "/" + feature.getVersion() + " from repo " + feature.getOriginalRepo());
                            feature.write(out);                            
                        }
                    }
                }
            }
            out.println("</features>");
            getLog().info("...done!");
        } catch (Exception e) {
            getLog().error(e);
            throw new MojoExecutionException("Unable to create " + outputFile.getAbsolutePath() + " file: " + e, e);
        } finally {
            if (out != null) {
                out.close();
            }
        }

    }

    private void retrieveDescriptorsRecursively(String uri, Set<String> bundles, Map<String, Feature> featuresMap) throws Exception {
        if (visitedDescriptors.containsKey(uri)) {
            getLog().debug("Descriptor already visited: " + uri);

            return;
        }
        getLog().info("Reading repository " + uri);
        visitedDescriptors.put(uri, uri);

        Repository repo = new Repository(uri, URI.create(translateFromMaven(uri.replaceAll(" ", "%20"))));
        for (Feature f : repo.getFeatures()) {
            featuresMap.put(f.getName() + "/" + f.getVersion(), f);
        }
        if (resolveDefinedRepositoriesRecursively) {
            for (String r : repo.getDefinedRepositories()) {
                retrieveDescriptorsRecursively(r, bundles, featuresMap);
            }
        }

    }

    // resolves the bundle in question
    // TODO neither remoteRepos nor bundle's Repository are used, only the local repo?????
//    private void resolveBundle(Artifact bundle, List<ArtifactRepository> remoteRepos) throws IOException, MojoFailureException {
//        // TODO consider DefaultRepositoryLayout
//        String dir = bundle.getGroupId().replace('.', '/') + "/" + bundle.getArtifactId() + "/" + bundle.getBaseVersion() + "/";
//        String name = bundle.getArtifactId() + "-" + bundle.getBaseVersion()
//                + (bundle.getClassifier() != null ? "-" + bundle.getClassifier() : "") + "." + bundle.getType();
//
//        try {
//            getLog().info("Copying bundle: " + bundle);
//            resolver.resolve(bundle, remoteRepos, localRepo);
//            copy(new FileInputStream(bundle.getFile()), repository, name, dir, new byte[8192]);
//        } catch (ArtifactResolutionException e) {
//            if (failOnArtifactResolutionError) {
//                throw new MojoFailureException("Can't resolve bundle " + bundle, e);
//            }
//            getLog().error("Can't resolve bundle " + bundle, e);
//        } catch (ArtifactNotFoundException e) {
//            if (failOnArtifactResolutionError) {
//                throw new MojoFailureException("Can't resolve bundle " + bundle, e);
//            }
//            getLog().error("Can't resolve bundle " + bundle, e);
//        }
//    }


    public static class Feature {

        private String name;
        private String version;
        private List<SimpleArtifact> dependencies = new ArrayList<SimpleArtifact>();
        private List<SimpleArtifact> bundles = new ArrayList<SimpleArtifact>();
        private Map<String, Map<String, String>> configs = new HashMap<String, Map<String, String>>();
        private List<SimpleArtifact> configFiles = new ArrayList<SimpleArtifact>();
        private String originalRepo;


        public Feature(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getOriginalRepo() {
            return originalRepo;
        }

        public void setOriginalRepo(String originalRepo) {
            this.originalRepo = originalRepo;
        }

        public List<SimpleArtifact> getDependencies() {
            return dependencies;
        }

        public List<SimpleArtifact> getBundles() {
            return bundles;
        }

        public Map<String, Map<String, String>> getConfigurations() {
            return configs;
        }

        public List<SimpleArtifact> getConfigFiles() {
            return configFiles;
        }

        public void addDependency(SimpleArtifact dependency) {
            dependencies.add(dependency);
        }

        public void addBundle(SimpleArtifact bundle) {
            bundles.add(bundle);
        }

        public void addConfig(String name, Map<String, String> properties) {
            configs.put(name, properties);
        }

        public void addConfigFile(SimpleArtifact configFile) {
            configFiles.add(configFile);
        }

        private String writeAttr(String name, String value) {
            if (value != null && value.length() > 0) {
                return " " + name + "=" + "\"" + value + "\"";
            } else {
                return "";
            }
        }

        public void write(PrintStream out) {

            out.println("\n\t<feature" + writeAttr("name", name) + writeAttr("version", version) + ">");

            for (SimpleArtifact a : dependencies) {
                out.println("\t\t<feature" + writeAttr("version", a.getVersion()) + ">" + a.getName() + "</feature>");
            }
            for (SimpleArtifact a : bundles) {
                out.println("\t\t<bundle>" + a.getName() + "</bundle>");
            }
            for (SimpleArtifact a : configFiles) {
                out.println("\t\t<configfile" + writeAttr("finalname", a.getAttr("finalname")) + ">" + a.getName()
                        + "</configfile>");
            }
            out.println("\t</feature>");

        }
    }

    public static class Repository {

        private URI uri;
        private List<Feature> features;
        private List<String> repositories;
        private String urlString;

        public Repository(String urlString, URI uri) {
            this.uri = uri;
            this.urlString = urlString;
        }

        public URI getURI() {
            return uri;
        }
        
        public Feature[] getFeatures() throws Exception {
            if (features == null) {
                loadFeatures();
            }
            return features.toArray(new Feature[features.size()]);
        }

        public String[] getDefinedRepositories() throws Exception {
            if (repositories == null) {
                loadRepositories();
            }
            return repositories.toArray(new String[repositories.size()]);
        }

        private void loadRepositories() throws IOException {
            try {
                repositories = new ArrayList<String>();
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                Document doc = factory.newDocumentBuilder().parse(uri.toURL().openStream());
                NodeList nodes = doc.getDocumentElement().getChildNodes();
                for (int i = 0; i < nodes.getLength(); i++) {
                    org.w3c.dom.Node node = nodes.item(i);
                    if (!(node instanceof Element) || !"repository".equals(node.getNodeName())) {
                        continue;
                    }
                    Element e = (Element) nodes.item(i);
                    repositories.add(e.getTextContent().trim());
                }
            } catch (SAXException e) {
                throw (IOException) new IOException().initCause(e);
            } catch (ParserConfigurationException e) {
                throw (IOException) new IOException().initCause(e);
            }
        }

        private void loadFeatures() throws IOException {
            try {
                features = new ArrayList<Feature>();
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                Document doc = factory.newDocumentBuilder().parse(uri.toURL().openStream());
                NodeList nodes = doc.getDocumentElement().getChildNodes();
                for (int i = 0; i < nodes.getLength(); i++) {
                    org.w3c.dom.Node node = nodes.item(i);
                    if (!(node instanceof Element) || !"feature".equals(node.getNodeName())) {
                        continue;
                    }
                    Element e = (Element) nodes.item(i);
                    String name = e.getAttribute("name");
                    String version = e.getAttribute("version");
                    Feature f = new Feature(name);
                    f.setVersion(version);
                    f.setOriginalRepo(urlString);
                    NodeList featureNodes = e.getElementsByTagName("feature");
                    for (int j = 0; j < featureNodes.getLength(); j++) {
                        Element b = (Element) featureNodes.item(j);
                        SimpleArtifact a = new SimpleArtifact(b.getTextContent(), b.getAttribute("version"));
                        f.addDependency(a);
                    }
                    NodeList configNodes = e.getElementsByTagName("config");
                    for (int j = 0; j < configNodes.getLength(); j++) {
                        Element c = (Element) configNodes.item(j);
                        String cfgName = c.getAttribute("name");
                        String data = c.getTextContent();
                        Properties properties = new Properties();
                        properties.load(new ByteArrayInputStream(data.getBytes()));
                        Map<String, String> hashtable = new Hashtable<String, String>();
                        for (Object key : properties.keySet()) {
                            String n = key.toString();
                            hashtable.put(n, properties.getProperty(n));
                        }
                        f.addConfig(cfgName, hashtable);
                    }
                    NodeList configFileNodes = e.getElementsByTagName("configfile");
                    for (int j = 0; j < configFileNodes.getLength(); j++) {
                        Element c = (Element) configFileNodes.item(j);
                        SimpleArtifact a = new SimpleArtifact(c.getTextContent());
                        a.addAttr(new SimpleArtifact.Attr("finalname", c.getAttribute("finalname")));
                        f.addConfigFile(a);
                    }
                    NodeList bundleNodes = e.getElementsByTagName("bundle");
                    for (int j = 0; j < bundleNodes.getLength(); j++) {
                        Element b = (Element) bundleNodes.item(j);
                        SimpleArtifact a = new SimpleArtifact(b.getTextContent(), b.getAttribute("version"));
                        f.addBundle(a);
                    }
                    features.add(f);
                }
            } catch (SAXException e) {
                throw (IOException) new IOException().initCause(e);
            } catch (ParserConfigurationException e) {
                throw (IOException) new IOException().initCause(e);
            }
        }

    }

    public static class SimpleArtifact {

        static class Attr {

            public Attr(String name, String value) {
                this.name = name;
                this.value = value;
            }


            String name;
            String value;
        }


        private String name;
        private String version;
        private List<Attr> attributes = new ArrayList<Attr>();


        public SimpleArtifact(String name) {
            this.name = name;
        }

        public SimpleArtifact(String name, String version) {
            this.name = name;
            this.version = version;
        }

        public String getName() {
            return name;
        }

        public String getVersion() {
            return version;
        }

        public void addAttr(Attr attr) {
            attributes.add(attr);
        }

        public String getAttr(String attr) {
            for (Attr a : attributes) {
                if (a.name.equals(attr)) {
                    return a.value;
                }
            }
            return null;
        }

        public List<Attr> getAttributes() {
            return attributes;
        }

        public void setAttributes(List<Attr> attributes) {
            this.attributes = attributes;
        }

    }
}
