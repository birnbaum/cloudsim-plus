/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */
package org.cloudbus.cloudsim.schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.Vm;

/**
 * VmSchedulerSpaceShared is a VMM allocation policy that allocates one or more
 * PEs from a host to a Virtual Machine Monitor (VMM), and doesn't allow sharing
 * of PEs. The allocated PEs will be used until the VM finishes running. If
 * there is no enough free PEs as required by a VM, or whether the available PEs
 * doesn't have enough capacity, the allocation fails. In the case of fail, no
 * PE is allocated to the requesting VM.
 *
 * @author Rodrigo N. Calheiros
 * @author Anton Beloglazov
 * @since CloudSim Toolkit 1.0
 */
public class VmSchedulerSpaceShared extends VmSchedulerAbstract {

    /**
     * A map between each VM and its allocated PEs, where the key is a VM ID and
     * the value a list of PEs allocated to VM.
     */
    private Map<String, List<Pe>> peAllocationMap;

    /**
     * The list of free PEs yet available in the host.
     */
    private List<Pe> freePesList;

    /**
     * Instantiates a new vm space-shared scheduler.
     *
     * @param pelist the pelist
     */
    public VmSchedulerSpaceShared(List<Pe> pelist) {
        super(pelist);
        setPeAllocationMap(new HashMap<>());
        setFreePesList(new ArrayList<>(pelist));
        getFreePesList().addAll(pelist);
    }

    @Override
    public boolean allocatePesForVm(Vm vm, List<Double> mipsShare) {
        // if there is no enough free PEs, fails
        if (getFreePesList().size() < mipsShare.size()) {
            return false;
        }

        List<Pe> selectedPes = new ArrayList<>();
        Iterator<Pe> peIterator = getFreePesList().iterator();
        Pe pe = peIterator.next();
        double totalMips = 0;
        for (Double mips : mipsShare) {
            if (mips <= pe.getMips()) {
                selectedPes.add(pe);
                if (!peIterator.hasNext()) {
                    break;
                }
                pe = peIterator.next();
                totalMips += mips;
            }
        }
        if (mipsShare.size() > selectedPes.size()) {
            return false;
        }

        getFreePesList().removeAll(selectedPes);

        getPeAllocationMap().put(vm.getUid(), selectedPes);
        getMipsMap().put(vm.getUid(), mipsShare);
        setAvailableMips(getAvailableMips() - totalMips);
        return true;
    }

    @Override
    public void deallocatePesForVm(Vm vm) {
        getFreePesList().addAll(getPeAllocationMap().get(vm.getUid()));
        getPeAllocationMap().remove(vm.getUid());

        double totalMips = 0;
        for (double mips : getMipsMap().get(vm.getUid())) {
            totalMips += mips;
        }
        setAvailableMips(getAvailableMips() + totalMips);

        getMipsMap().remove(vm.getUid());
    }

    /**
     * Sets the pe allocation map.
     *
     * @param peAllocationMap the pe allocation map
     */
    protected final void setPeAllocationMap(Map<String, List<Pe>> peAllocationMap) {
        this.peAllocationMap = peAllocationMap;
    }

    /**
     * Gets the pe allocation map.
     *
     * @return the pe allocation map
     */
    protected Map<String, List<Pe>> getPeAllocationMap() {
        return peAllocationMap;
    }

    /**
     * Sets the free pes list.
     *
     * @param freePesList the new free pes list
     */
    protected final void setFreePesList(List<Pe> freePesList) {
        this.freePesList = freePesList;
    }

    /**
     * Gets the free pes list.
     *
     * @return the free pes list
     */
    protected final List<Pe> getFreePesList() {
        return freePesList;
    }

}