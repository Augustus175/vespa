// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * \class storage::FileStorHandlerImpl
 * \ingroup storage
 *
 * \brief Common resource for filestor threads.
 *
 * This class implements all locking related stuff between filestor threads.
 * It keeps the various filestor thread queues, and implement thread safe
 * functions for inserting, removing and moving stuff in the queues. In addition
 * it makes it possible to lock buckets, by keeping track of current operation
 * for various threads, and not allowing them to get another operation of a
 * locked bucket until unlocked.
 */

#pragma once

#include "filestorhandler.h"
#include "mergestatus.h"
#include <vespa/document/bucket/bucketid.h>
#include <vespa/metrics/metrics.h>
#include <vespa/storage/common/servicelayercomponent.h>
#include <vespa/storageframework/generic/metric/metricupdatehook.h>
#include <boost/multi_index_container.hpp>
#include <boost/multi_index/identity.hpp>
#include <boost/multi_index/member.hpp>
#include <boost/multi_index/mem_fun.hpp>
#include <boost/multi_index/ordered_index.hpp>
#include <boost/multi_index/sequenced_index.hpp>
#include <vespa/storage/common/messagesender.h>
#include <vespa/vespalib/stllike/hash_map.h>
#include <atomic>
#include <optional>

namespace storage {

class FileStorDiskMetrics;
class FileStorStripeMetrics;
class StorBucketDatabase;
class AbortBucketOperationsCommand;

namespace bmi = boost::multi_index;

class FileStorHandlerImpl : private framework::MetricUpdateHook,
                            private ResumeGuard::Callback,
                            public MessageSender {
public:
    typedef FileStorHandler::DiskState DiskState;
    typedef FileStorHandler::RemapInfo RemapInfo;

    struct MessageEntry {
        std::shared_ptr<api::StorageMessage> _command;
        metrics::MetricTimer _timer;
        document::Bucket _bucket;
        uint8_t _priority;

        MessageEntry(const std::shared_ptr<api::StorageMessage>& cmd, const document::Bucket &bId);
        MessageEntry(MessageEntry &&) noexcept ;
        MessageEntry(const MessageEntry &);
        MessageEntry & operator = (const MessageEntry &) = delete;
        ~MessageEntry();

        bool operator<(const MessageEntry& entry) const {
            return (_priority < entry._priority);
        }
    };

    using PriorityOrder = bmi::ordered_non_unique<bmi::identity<MessageEntry> >;
    using BucketOrder = bmi::ordered_non_unique<bmi::member<MessageEntry, document::Bucket, &MessageEntry::_bucket>>;

    using PriorityQueue = bmi::multi_index_container<MessageEntry, bmi::indexed_by<bmi::sequenced<>, PriorityOrder, BucketOrder>>;

    using PriorityIdx = bmi::nth_index<PriorityQueue, 1>::type;
    using BucketIdx = bmi::nth_index<PriorityQueue, 2>::type;

    class Disk;

    class Stripe {
    public:
        struct LockEntry {
            uint32_t                timestamp;
            uint8_t                 priority;
            api::MessageType::Id    msgType;
            api::StorageMessage::Id msgId;

            LockEntry() : timestamp(0), priority(0), msgType(), msgId(0) { }

            LockEntry(uint8_t priority_, api::MessageType::Id msgType_, api::StorageMessage::Id msgId_)
                : timestamp(time(nullptr)), priority(priority_), msgType(msgType_), msgId(msgId_)
            { }
        };

        struct MultiLockEntry {
            std::optional<LockEntry> _exclusiveLock;
            using SharedLocks = vespalib::hash_map<api::StorageMessage::Id, LockEntry>;
            SharedLocks _sharedLocks;
        };

        Stripe(const FileStorHandlerImpl & owner, MessageSender & messageSender);
        ~Stripe();
        void flush();
        bool schedule(MessageEntry messageEntry);
        void waitUntilNoLocks() const;
        void abort(std::vector<std::shared_ptr<api::StorageReply>> & aborted, const AbortBucketOperationsCommand& cmd);
        void waitInactive(const AbortBucketOperationsCommand& cmd) const;

        void broadcast() {
            vespalib::MonitorGuard guard(_lock);
            guard.broadcast();
        }
        size_t getQueueSize() const {
            vespalib::MonitorGuard guard(_lock);
            return _queue.size();
        }
        void release(const document::Bucket & bucket, api::LockingRequirements reqOfReleasedLock,
                     api::StorageMessage::Id lockMsgId);

        bool isLocked(const vespalib::MonitorGuard &, const document::Bucket&,
                      api::LockingRequirements lockReq) const noexcept;

        void lock(const vespalib::MonitorGuard &, const document::Bucket & bucket,
                  api::LockingRequirements lockReq, const LockEntry & lockEntry);

        std::shared_ptr<FileStorHandler::BucketLockInterface> lock(const document::Bucket & bucket, api::LockingRequirements lockReq);
        void failOperations(const document::Bucket & bucket, const api::ReturnCode & code);

        FileStorHandler::LockedMessage getNextMessage(uint32_t timeout, Disk & disk);
        FileStorHandler::LockedMessage & getNextMessage(FileStorHandler::LockedMessage& lock);
        void dumpQueue(std::ostream & os) const;
        void dumpActiveHtml(std::ostream & os) const;
        void dumpQueueHtml(std::ostream & os) const;
        vespalib::Monitor & exposeLock() { return _lock; }
        PriorityQueue & exposeQueue() { return _queue; }
        BucketIdx & exposeBucketIdx() { return bmi::get<2>(_queue); }
        void setMetrics(FileStorStripeMetrics * metrics) { _metrics = metrics; }
    private:
        bool hasActive(vespalib::MonitorGuard & monitor, const AbortBucketOperationsCommand& cmd) const;
        // Precondition: the bucket used by `iter`s operation is not locked in a way that conflicts
        // with its locking requirements.
        FileStorHandler::LockedMessage getMessage(vespalib::MonitorGuard & guard, PriorityIdx & idx,
                                                  PriorityIdx::iterator iter);
        using LockedBuckets = vespalib::hash_map<document::Bucket, MultiLockEntry, document::Bucket::hash>;
        const FileStorHandlerImpl  &_owner;
        MessageSender              &_messageSender;
        FileStorStripeMetrics      *_metrics;
        vespalib::Monitor           _lock;
        PriorityQueue               _queue;
        LockedBuckets               _lockedBuckets;
    };
    struct Disk {
        FileStorDiskMetrics * metrics;

        /**
         * No assumption on memory ordering around disk state reads should
         * be made by callers.
         */
        DiskState getState() const noexcept {
            return state.load(std::memory_order_relaxed);
        }
        /**
         * No assumption on memory ordering around disk state writes should
         * be made by callers.
         */
        void setState(DiskState s) noexcept {
            state.store(s, std::memory_order_relaxed);
        }

        Disk(const FileStorHandlerImpl & owner, MessageSender & messageSender, uint32_t numThreads);
        Disk(Disk &&) noexcept;
        ~Disk();

        bool isClosed() const noexcept { return getState() == DiskState::CLOSED; }

        void flush();
        void broadcast();
        bool schedule(const std::shared_ptr<api::StorageMessage>& msg);
        void waitUntilNoLocks() const;
        std::vector<std::shared_ptr<api::StorageReply>> abort(const AbortBucketOperationsCommand& cmd);
        void waitInactive(const AbortBucketOperationsCommand& cmd) const;
        FileStorHandler::LockedMessage getNextMessage(uint32_t stripeId, uint32_t timeout) {
            return _stripes[stripeId].getNextMessage(timeout, *this);
        }
        FileStorHandler::LockedMessage & getNextMessage(uint32_t stripeId, FileStorHandler::LockedMessage & lck) {
            return _stripes[stripeId].getNextMessage(lck);
        }
        std::shared_ptr<FileStorHandler::BucketLockInterface>
        lock(const document::Bucket & bucket, api::LockingRequirements lockReq) {
            return stripe(bucket).lock(bucket, lockReq);
        }
        void failOperations(const document::Bucket & bucket, const api::ReturnCode & code) {
            stripe(bucket).failOperations(bucket, code);
        }

        uint32_t getQueueSize() const noexcept;
        uint32_t getNextStripeId() { return (_nextStripeId++)%_stripes.size(); }
        std::string dumpQueue() const;
        void dumpActiveHtml(std::ostream & os) const;
        void dumpQueueHtml(std::ostream & os) const;
        static uint64_t dispersed_bucket_bits(const document::Bucket& bucket) noexcept {
            // Disperse bucket bits by multiplying with the 64-bit FNV-1 prime.
            // This avoids an inherent affinity between the LSB of a bucket's bits
            // and the stripe an operation ends up on.
            return bucket.getBucketId().getId() * 1099511628211ULL;
        }
        Stripe & stripe(const document::Bucket & bucket) {
            return _stripes[dispersed_bucket_bits(bucket) % _stripes.size()];
        }
        std::vector<Stripe> & getStripes() { return _stripes; }
    private:
        uint32_t              _nextStripeId;
        std::vector<Stripe>   _stripes;
        std::atomic<DiskState> state;
    };

    class BucketLock : public FileStorHandler::BucketLockInterface {
    public:
        // TODO refactor, too many params
        BucketLock(const vespalib::MonitorGuard & guard, Stripe& disk, const document::Bucket &bucket,
                   uint8_t priority, api::MessageType::Id msgType, api::StorageMessage::Id,
                   api::LockingRequirements lockReq);
        ~BucketLock();

        const document::Bucket &getBucket() const override { return _bucket; }
        api::LockingRequirements lockingRequirements() const noexcept override { return _lockReq; }

    private:
        Stripe & _stripe;
        document::Bucket _bucket;
        api::StorageMessage::Id _uniqueMsgId;
        api::LockingRequirements _lockReq;
    };

    FileStorHandlerImpl(uint32_t numStripes, MessageSender&, FileStorMetrics&,
                        const spi::PartitionStateList&, ServiceLayerComponentRegister&);

    ~FileStorHandlerImpl();
    void setGetNextMessageTimeout(uint32_t timeout) { _getNextMessageTimeout = timeout; }

    void flush(bool killPendingMerges);
    void setDiskState(uint16_t disk, DiskState state);
    DiskState getDiskState(uint16_t disk) const;
    void close();
    bool schedule(const std::shared_ptr<api::StorageMessage>&, uint16_t disk);

    FileStorHandler::LockedMessage getNextMessage(uint16_t disk, uint32_t stripeId);

    FileStorHandler::LockedMessage & getNextMessage(uint16_t disk, uint32_t stripeId, FileStorHandler::LockedMessage& lock);

    enum Operation { MOVE, SPLIT, JOIN };
    void remapQueue(const RemapInfo& source, RemapInfo& target, Operation op);

    void remapQueue(const RemapInfo& source, RemapInfo& target1, RemapInfo& target2, Operation op);

    void failOperations(const document::Bucket & bucket, uint16_t disk, const api::ReturnCode & code) {
        _diskInfo[disk].failOperations(bucket, code);
    }
    void sendCommand(const std::shared_ptr<api::StorageCommand>&) override;
    void sendReply(const std::shared_ptr<api::StorageReply>&) override;

    void getStatus(std::ostream& out, const framework::HttpUrlPath& path) const;

    uint32_t getQueueSize() const;
    uint32_t getQueueSize(uint16_t disk) const;
    uint32_t getNextStripeId(uint32_t disk);

    std::shared_ptr<FileStorHandler::BucketLockInterface>
    lock(const document::Bucket & bucket, uint16_t disk, api::LockingRequirements lockReq) {
        return _diskInfo[disk].lock(bucket, lockReq);
    }

    void addMergeStatus(const document::Bucket&, MergeStatus::SP);
    MergeStatus& editMergeStatus(const document::Bucket&);
    bool isMerging(const document::Bucket&) const;
    uint32_t getNumActiveMerges() const;
    void clearMergeStatus(const document::Bucket&, const api::ReturnCode*);

    std::string dumpQueue(uint16_t disk) const {
        return _diskInfo[disk].dumpQueue();
    }
    ResumeGuard pause();
    void resume() override;
    void abortQueuedOperations(const AbortBucketOperationsCommand& cmd);

private:
    const spi::PartitionStateList& _partitions;
    ServiceLayerComponent _component;
    std::vector<Disk> _diskInfo;
    MessageSender& _messageSender;
    const document::BucketIdFactory& _bucketIdFactory;

    vespalib::Lock _mergeStatesLock;

    std::map<document::Bucket, MergeStatus::SP> _mergeStates;

    uint32_t _getNextMessageTimeout;

    vespalib::Monitor _pauseMonitor;
    std::atomic<bool> _paused;

    void reply(api::StorageMessage&, DiskState state) const;

    // Returns the index in the targets array we are sending to, or -1 if none of them match.
    int calculateTargetBasedOnDocId(const api::StorageMessage& msg, std::vector<RemapInfo*>& targets);

    /**
     * If FileStor layer is explicitly paused, try to wait a single time, then
     * recheck pause status. Returns true if filestor isn't paused at the time
     * of the first check or after the wait, false if it's still paused.
     */
    bool tryHandlePause(uint16_t disk) const;

    /**
     * Checks whether the entire filestor layer is paused.
     * Since there should be no data or synchronization dependencies on
     * _paused, use relaxed atomics.
     */
    bool isPaused() const { return _paused.load(std::memory_order_relaxed); }

    /**
     * Return whether msg has timed out based on waitTime and the message's
     * specified timeout.
     */
    static bool messageTimedOutInQueue(const api::StorageMessage& msg, uint64_t waitTime);

    /**
     * Creates and returns a reply with api::TIMEOUT return code for msg.
     * Swaps (invalidates) context from msg into reply.
     */
    static std::unique_ptr<api::StorageReply> makeQueueTimeoutReply(api::StorageMessage& msg);
    static bool messageMayBeAborted(const api::StorageMessage& msg);
    void abortQueuedCommandsForBuckets(Disk& disk, const AbortBucketOperationsCommand& cmd);

    // Update hook
    void updateMetrics(const MetricLockGuard &) override;

    document::Bucket
    remapMessage(api::StorageMessage& msg, const document::Bucket &source, Operation op,
                 std::vector<RemapInfo*>& targets, uint16_t& targetDisk, api::ReturnCode& returnCode);

    void remapQueueNoLock(Disk& from, const RemapInfo& source, std::vector<RemapInfo*>& targets, Operation op);

    /**
     * Waits until the queue has no pending operations (i.e. no locks are
     * being held.
     */
    void waitUntilNoLocks();
};

} // storage
