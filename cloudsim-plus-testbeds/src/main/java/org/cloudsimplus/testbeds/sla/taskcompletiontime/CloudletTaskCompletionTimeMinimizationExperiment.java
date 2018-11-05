/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 *
 *     Copyright (C) 2015-2018 Universidade da Beira Interior (UBI, Portugal) and
 *     the Instituto Federal de Educação Ciência e Tecnologia do Tocantins (IFTO, Brazil).
 *
 *     This file is part of CloudSim Plus.
 *
 *     CloudSim Plus is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     CloudSim Plus is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with CloudSim Plus. If not, see <http://www.gnu.org/licenses/>.
 */
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.cloudsimplus.testbeds.sla.taskcompletiontime;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.Machine;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.distributions.ContinuousDistribution;
import org.cloudbus.cloudsim.distributions.UniformDistr;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.ResourceProvisioner;
import org.cloudbus.cloudsim.provisioners.ResourceProvisionerSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerCompletelyFair;
import org.cloudbus.cloudsim.schedulers.vm.VmScheduler;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.util.ResourceLoader;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelDynamic;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.slametrics.SlaContract;
import org.cloudsimplus.testbeds.ExperimentRunner;
import org.cloudsimplus.testbeds.SimulationExperiment;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

import static java.util.Comparator.comparingDouble;
import static java.util.Comparator.comparingLong;
import static java.util.stream.Collectors.toList;
import static org.cloudsimplus.testbeds.sla.taskcompletiontime.CloudletTaskCompletionTimeMinimizationRunner.*;

/**
 * An experiment that tries to minimize task completion time
 * by selecting as the VM to run a Cloudlet,
 * that one that minimizes the Cloudlet completion time.
 *
 * <p>
 * It uses the {@link DatacenterBroker#setVmMapper(Function)}
 * method to define the policy used to map Cloudlets to VMs.
 * The workload is defined as a set of randomly created Cloudlets.
 * </p>
 *
 * <p>For more details, check
 * <a href="http://www.di.ubi.pt/~mario/files/MScDissertation-RaysaOliveira.pdf">Raysa Oliveira's Master Thesis (only in Portuguese)</a>.</p>
 *
 * @author raysaoliveira
 * @see #selectVmForCloudlet(Cloudlet)
 */
public final class CloudletTaskCompletionTimeMinimizationExperiment extends SimulationExperiment {
    private static final int SCHEDULING_INTERVAL = 5;

    private static final int HOSTS = 50;
    private static final int HOST_PES = 32;

    private List<Host> hostList;
    private static List<Vm> vmList;
    private List<Cloudlet> cloudletList;

    private final ContinuousDistribution randCloudlet, randVm, randCloudletPes, randMipsVm;

    private int createdCloudlets;
    private int createsVms;

    /**
     * The file containing the SLA Contract in JSON format.
     */
    private static final String SLA_CONTRACTS_LIST = "sla-files.txt";

    /**
     * A map where the key is a {@link DatacenterBroker} and each value
     * the {@link SlaContract} of that broker.
     */
    private Map<DatacenterBroker, SlaContract> contractsMap;

    /**
     * Sorts the Cloudlets before submitting them to the Broker, so that
     * Cloudlets with larger length will be mapped for a VM first than lower
     * ones.
     */
    private static final Comparator<Cloudlet> SORT_CLOUDLETS_BY_LENGTH_REVERSED = comparingDouble(Cloudlet::getLength).reversed();

    private CloudletTaskCompletionTimeMinimizationExperiment(final long seed) {
        this(0, null, seed);
    }

    CloudletTaskCompletionTimeMinimizationExperiment(final int index, final ExperimentRunner runner) {
        this(index, runner, -1);
    }

    private CloudletTaskCompletionTimeMinimizationExperiment(final int index, final ExperimentRunner runner, final long seed) {
        super(index, runner, seed);
        this.randCloudlet = new UniformDistr(getSeed());
        this.randVm = new UniformDistr(getSeed()+2);
        this.randCloudletPes = new UniformDistr(getSeed()+3);
        this.randMipsVm = new UniformDistr(getSeed()+4);
        contractsMap = new HashMap<>();
    }

    /**
     * Read all SLA contracts registered in the {@link #SLA_CONTRACTS_LIST}.
     * When the brokers are created, it is ensured the number of brokers
     * is equals to the number of SLA contracts in the {@link #SLA_CONTRACTS_LIST}.
     */
    private void readTheSlaContracts() throws IOException {
        for (final String file: readContractList()) {
            SlaContract contract = SlaContract.getInstance(file);
            contractsMap.put(getFirstBroker(), contract);
        }
    }

    private List<String> readContractList() throws FileNotFoundException {
        return ResourceLoader
            .getBufferedReader(getClass(), SLA_CONTRACTS_LIST)
            .lines()
            .filter(l -> !l.startsWith("#"))
            .filter(l -> !l.trim().isEmpty())
            .collect(toList());
    }

    private DatacenterBroker getFirstBroker() {
        return getBrokerList().stream().findFirst().orElse(DatacenterBroker.NULL);
    }

    @Override
    public final void printResults() {
        final DatacenterBroker broker0 = getFirstBroker();
        final List<Cloudlet> finishedCloudlets = broker0.getCloudletFinishedList();
        final Comparator<Cloudlet> sortByVmId = comparingDouble(c -> c.getVm().getId());
        final Comparator<Cloudlet> sortByStartTime = comparingDouble(Cloudlet::getExecStartTime);
        finishedCloudlets.sort(sortByVmId.thenComparing(sortByStartTime));

        new CloudletsTableBuilder(finishedCloudlets).build();
    }

    @Override
    protected List<Cloudlet> createCloudlets() {
        cloudletList = new ArrayList<>(CLOUDLETS);
        for (int i = 0; i < CLOUDLETS; i++) {
            cloudletList.add(createCloudlet());
        }

        return cloudletList;
    }

    private Cloudlet createCloudlet() {
        final UtilizationModel model = new UtilizationModelDynamic(0.1);
        final int id = createdCloudlets++;
        final int i = (int) (randCloudlet.sample() * CLOUDLET_LENGTHS.length);
        final int p = (int) (randCloudletPes.sample() * CLOUDLET_PES.length);
        final long length = CLOUDLET_LENGTHS[i];
        final long pes = CLOUDLET_PES[p];
        return new CloudletSimple(id, length, pes)
                .setFileSize(1024)
                .setOutputSize(1024)
                .setUtilizationModelBw(model)
                .setUtilizationModelRam(model)
                .setUtilizationModelCpu(new UtilizationModelFull());
    }

    @Override
    protected DatacenterSimple createDatacenter() {
        DatacenterSimple dc = super.createDatacenter();
        dc.setSchedulingInterval(SCHEDULING_INTERVAL);
        return dc;
    }

    @Override
    protected List<Vm> createVms(final DatacenterBroker broker) {
        vmList = new ArrayList<>(VMS);
        for (int i = 0; i < VMS; i++) {
            Vm vm = createVm();
            //  createHorizontalVmScaling(vm);
            vmList.add(vm);
        }
        return vmList;
    }

    /**
     * Creates a Vm object.
     *
     * @return the created Vm
     */
    private Vm createVm() {
        final int id = createsVms++;
        final int i = (int) (randVm.sample() * VM_PES.length);
        final int m = (int) (randMipsVm.sample() * MIPS_VM.length);
        final int pes = VM_PES[i];
        final int mips = MIPS_VM[m];


        Vm vm = new VmSimple(id, mips, pes)
                .setRam(512).setBw(1000).setSize(10000)
                .setCloudletScheduler(new CloudletSchedulerCompletelyFair());
        return vm;
    }

    @Override
    protected List<Host> createHosts() {
        hostList = new ArrayList<>(HOSTS);
        for (int i = 0; i < HOSTS; i++) {
            hostList.add(createHost());
        }
        return hostList;
    }

    private Host createHost() {
        final List<Pe> pesList = new ArrayList<>(HOST_PES);
        for (int i = 0; i < HOST_PES; i++) {
            pesList.add(new PeSimple(10000, new PeProvisionerSimple()));
        }

        ResourceProvisioner ramProvisioner = new ResourceProvisionerSimple();
        ResourceProvisioner bwProvisioner = new ResourceProvisionerSimple();
        VmScheduler vmScheduler = new VmSchedulerTimeShared();
        final int id = hostList.size();
        return new HostSimple(40960, 100000, 100000, pesList)
                .setRamProvisioner(ramProvisioner)
                .setBwProvisioner(bwProvisioner)
                .setVmScheduler(vmScheduler);
    }

    @Override
    protected void createBrokers() {
        super.createBrokers();
        final DatacenterBroker broker0 = getFirstBroker();
        broker0.setVmMapper(this::selectVmForCloudlet);
        broker0.setCloudletComparator(SORT_CLOUDLETS_BY_LENGTH_REVERSED);

        try {
            readTheSlaContracts();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Selects a VM to run a Cloudlet which minimizes the Cloudlet completion
     * time.
     *
     * @param cloudlet the Cloudlet to select a VM to
     * @return the selected Vm
     */
    private Vm selectVmForCloudlet(final Cloudlet cloudlet) {
        final List<Vm> execVms = cloudlet.getBroker().getVmExecList();

        final Comparator<Vm> sortByFreePesNumber = comparingLong(this::getExpectedNumberOfFreeVmPes);
        final Comparator<Vm> sortByExpectedCloudletCompletionTime = comparingDouble(vm -> getExpectedCloudletCompletionTime(cloudlet, vm));
        execVms.sort(
            sortByExpectedCloudletCompletionTime.thenComparing(sortByFreePesNumber.reversed())
        );
        final Vm mostFreePesVm = execVms.stream().findFirst().orElse(Vm.NULL);

        final double taskCompletionTimeSlaContract = getTaskCompletionTimeFromContract(cloudlet.getBroker());

        return execVms.stream()
            .filter(vm -> getExpectedNumberOfFreeVmPes(vm) >= cloudlet.getNumberOfPes())
            .filter(vm -> getExpectedCloudletCompletionTime(cloudlet, vm) <= taskCompletionTimeSlaContract)
            .findFirst()
            .orElse(mostFreePesVm);
    }

    @Override
    protected DatacenterBroker createBroker() {
        return new DatacenterBrokerSimple(getCloudSim());
    }

    /**
     * Gets the expected amount of free PEs for a VM
     *
     * @param vm the VM to get the amount of free PEs
     * @return the number of PEs that are free or a negative value that indicate
     * there aren't free PEs (this negative number indicates the amount of
     * overloaded PEs)
     */
    private long getExpectedNumberOfFreeVmPes(final Vm vm) {
        final long totalPesForCloudletsOfVm
            = vm.getBroker().getCloudletCreatedList().stream()
            .filter(c -> c.getVm().equals(vm))
            .mapToLong(Cloudlet::getNumberOfPes)
            .sum();

        final long numberOfVmFreePes = vm.getNumberOfPes() - totalPesForCloudletsOfVm;

        System.out.printf(
            "\t\tTotal PEs of Cloudlets in %s: %d -> VM PEs: %d -> VM free PEs: %d",
            vm, totalPesForCloudletsOfVm, vm.getNumberOfPes(), numberOfVmFreePes);

        return numberOfVmFreePes;
    }

    private double getExpectedCloudletCompletionTime(final Cloudlet cloudlet, final Vm vm) {
        return cloudlet.getLength() / vm.getMips();
    }

    /**
     * Computes the Task Completion Time average for all finished Cloudlets on
     * this experiment.
     *
     * @return the Task Completion Time
     */
    double getTaskCompletionTimeAverage() {
        final SummaryStatistics cloudletTaskCompletionTime = new SummaryStatistics();
        final DatacenterBroker broker = getBrokerList().stream()
                .findFirst()
                .orElse(DatacenterBroker.NULL);
        broker.getCloudletFinishedList().stream()
                .map(c -> c.getFinishTime() - c.getLastDatacenterArrivalTime())
                .forEach(cloudletTaskCompletionTime::addValue);

        return cloudletTaskCompletionTime.getMean();
    }

    /**
     * Gets the Task Completion Time (TCT) defined in a {@link DatacenterBroker}'s {@link SlaContract}.
     * @param broker the {@link DatacenterBroker} to the TCT from the {@link SlaContract}
     * @return the broker SLA's TCT
     * @see #contractsMap
     */
    private double getTaskCompletionTimeFromContract(final DatacenterBroker broker) {
        return contractsMap.get(broker).getTaskCompletionTimeMetric().getMaxDimension().getValue();
    }

    /**
     * Gets the percentage of Cloudlets that met the task completion time,
     * according the the broker {@link #contractsMap SLA Contract}.
     * @return
     */
    double getPercentageOfCloudletsMeetingTaskCompletionTime() {
        final DatacenterBroker broker = getBrokerList().stream()
                .findFirst()
                .orElse(DatacenterBroker.NULL);

        final double taskCompletionTimeSlaContract = getTaskCompletionTimeFromContract(broker);
        double totalOfCloudletSlaSatisfied = broker.getCloudletFinishedList().stream()
                .map(c -> c.getFinishTime() - c.getLastDatacenterArrivalTime())
                .filter(rt -> rt <= taskCompletionTimeSlaContract)
                .count();
        return (totalOfCloudletSlaSatisfied * 100) / broker.getCloudletFinishedList().size();
    }

    /**
     * Gets the total number of vPEs (VM PEs) across all existing VMs.
     * @return
     */
    private double getSumPesVms() {
        return vmList.stream()
                .mapToDouble(Machine::getNumberOfPes)
                .sum();
    }

    private double getSumPesCloudlets() {
        return cloudletList.stream()
                .mapToDouble(Cloudlet::getNumberOfPes)
                .sum();
    }

    /**
     * Gets the ratio of the total number of vPEs (VM PEs across all existing VMs) divided by the
     * total number of required PEs of all Cloudlets.
     * That indicates the mean number of vPEs
     * that are available for each PE required by a Cloudlet, considering all
     * the existing Cloudlets. For instance, if the ratio is 0.5, in average,
     * two Cloudlets requiring one vPE will share that same vPE.
     * If the ratio is greater than one, there is more vPE that the required by all Cloudlets.
     * For instance, a ratio of 2 means there is 2 vPE for each PE required by a Cloudlet.
     *
     * @return the average of vPEs/CloudletsPEs ratio
     */
    double getRatioOfExistingVmPesToRequiredCloudletPes() {
        double sumPesVms = getSumPesVms();
        double sumPesCloudlets = getSumPesCloudlets();
        return sumPesVms / sumPesCloudlets;
    }

    /**
     * A main method just for test purposes.
     *
     * @param args
     */
    public static void main(String[] args) {
        final CloudletTaskCompletionTimeMinimizationExperiment exp = new CloudletTaskCompletionTimeMinimizationExperiment(344L);
        exp.setVerbose(true);
        exp.run();
        System.out.println();
        System.out.printf("Average Task Completion Time (TCT): %.2f seconds\n", exp.getTaskCompletionTimeAverage());
        for (DatacenterBroker broker : exp.getBrokerList()) {
            System.out.printf("%s SLA's TCT: %.2f seconds\n", broker, exp.getTaskCompletionTimeFromContract(broker));
        }

        System.out.printf("Percentage of cloudlet that met TCT (i.e, the execution time was less or equal to the TCT defined in the SLA): %.2f%%\n",  exp.getPercentageOfCloudletsMeetingTaskCompletionTime());
        System.out.printf("Ratio of existing total VM PEs to required Cloudlets PEs: %.2f\n", exp.getRatioOfExistingVmPesToRequiredCloudletPes());
    }
}
