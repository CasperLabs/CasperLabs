import { action, autorun, IObservableArray, observable, reaction, runInAction } from 'mobx';

import ErrorContainer from './ErrorContainer';
import { CasperService, encodeBase16 } from 'casperlabs-sdk';
import { BlockInfo, Event } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import { ToggleStore } from '../components/ToggleButton';
import { ToggleableSubscriber } from './ToggleableSubscriber';

const DEFAULT_DEPTH = 100;

export class DagStep {
  constructor(private container: DagContainer) {
  }

  private step = (f: () => number) => () => {
    this.maxRank = f();
    this.container.selectedBlock = undefined;
  };

  get maxRank() {
    return this.container.maxRank;
  }

  get depth() {
    return this.container.depth;
  }

  set maxRank(rank: number) {
    this.container.maxRank = rank;
  }

  private get currentMaxRank() {
    let blockRank =
      this.container.hasBlocks &&
      this.container
        .blocks![0].getSummary()!
        .getHeader()!
        .getJRank();
    return this.maxRank === 0 && blockRank ? blockRank : this.maxRank;
  }

  first = this.step(() => this.depth - 1);

  prev = this.step(() =>
    this.maxRank === 0 && this.currentMaxRank <= this.depth
      ? 0
      : this.currentMaxRank > this.depth
        ? this.currentMaxRank - this.depth
        : this.currentMaxRank
  );

  next = this.step(() => this.currentMaxRank + this.depth);

  last = this.step(() => 0);
}

export class DagContainer {
  @observable blocks: IObservableArray<BlockInfo> | null = null;
  @observable selectedBlock: BlockInfo | undefined = undefined;
  @observable depth = 10;
  @observable maxRank = 0;
  @observable validatorsListToggleStore: ToggleStore = new ToggleStore(false);
  @observable lastFinalizedBlock: BlockInfo | undefined = undefined;
  @observable hideBallotsToggleStore: ToggleStore = new ToggleStore(false);
  @observable hideBlockHashToggleStore: ToggleStore = new ToggleStore(false);
  toggleableSubscriber: ToggleableSubscriber;

  constructor(
    private errors: ErrorContainer,
    private casperService: CasperService
  ) {
    this.toggleableSubscriber = new ToggleableSubscriber(
      {
        blockAdded: true,
        blockFinalized: true
      },
      this.casperService,
      (e) => this.subscriberHandler(e),
      () => this.isLatestDag,
      () => this.refreshBlockDag()
    );

    // react to the change of maxRank and depth, so that outer components only need to set DAG's props
    // DAG manage the refresh by itself.
    reaction(() => {
      return [this.maxRank, this.depth]
    }, () => {
      this.refreshBlockDagAndSetupSubscriber();
    })
  }

  refreshWithDepthAndMaxRank(
    maxRankStr: string | null,
    depthStr: string | null
  ) {
    let maxRank = parseInt(maxRankStr || '') || 0;
    let depth = parseInt(depthStr || '') || DEFAULT_DEPTH;
    this.updateMaxRankAndDepth(maxRank, depth);
  }

  @action
  updateMaxRankAndDepth(rank: number, depth: number) {
    this.maxRank = rank;
    this.depth = depth;
  }

  get minRank() {
    return Math.max(0, this.maxRank - this.depth + 1);
  }

  get hasBlocks() {
    return this.blocks ? this.blocks.length > 0 : false;
  }

  get isLatestDag() {
    return this.maxRank === 0;
  }

  async selectByBlockHashBase16(blockHashBase16: string) {
    let selectedBlock = this.blocks!.find(
      x => encodeBase16(x.getSummary()!.getBlockHash_asU8()) === blockHashBase16
    );
    if (selectedBlock) {
      this.selectedBlock = selectedBlock;
    } else {
      await this.errors.capture(
        this.casperService.getBlockInfo(blockHashBase16, 0).then(block => {
          this.selectedBlock = block;
          let contained = this.blocks!.find(
            x =>
              encodeBase16(x.getSummary()!.getBlockHash_asU8()) ===
              blockHashBase16
          );
          if (!contained) {
            this.blocks!.push(block);
          }
        })
      );
    }
  }

  step = new DagStep(this);

  @action.bound
  private subscriberHandler(event: Event) {
    if (event.hasBlockAdded()) {
      let block = event.getBlockAdded()?.getBlock();
      if (block) {
        let index: number | undefined = this.blocks?.findIndex(
          b =>
            b.getSummary()?.getBlockHash_asB64() ===
            block!.getSummary()?.getBlockHash_asB64()
        );

        if (index === -1) {
          // blocks with rank < maxRank+1-depth will be culled
          let blockRank = block!.getSummary()!.getHeader()!.getJRank();
          let oldMaxRank = this.blocks ? this.blocks[0].getSummary()!.getHeader()!.getJRank() : 0;
          let maxRank = Math.max(oldMaxRank, blockRank);
          let culledThreshold = maxRank + 1 - this.depth;
          if (blockRank >= culledThreshold) {
            // The new block should be added to DAG.
            let remainingBlocks = this.blocks ? this.blocks.filter(b => {
              let rank = b.getSummary()?.getHeader()?.getJRank();
              if (rank !== undefined) {
                return rank >= culledThreshold;
              }
              return false;
            }) : [];
            // insert item to an ordered array
            // find first index I so that the newAddedBlock.jRank >= remainingBlocks[i].jRank
            let i = 0;
            for (; i < remainingBlocks.length; i++) {
              if (blockRank >= remainingBlocks[i].getSummary()!.getHeader()!.getJRank()) {
                break;
              }
            }
            remainingBlocks.splice(i, 0, block);
            runInAction(() => {
              this.blocks = observable.array(remainingBlocks);
            });
          } else {
            // otherwise ignore the new block and do nothing
          }
        }
      }
    } else if (event.hasNewFinalizedBlock()) {
      const directFinalizedBlockHash = event.getNewFinalizedBlock()!.getBlockHash_asB64();

      const orphanedBlocks = new Set(
        event
          .getNewFinalizedBlock()!
          .getIndirectlyOrphanedBlockHashesList_asB64()
      );
      const finalizedBlocks = new Set(
        event
          .getNewFinalizedBlock()!
          .getIndirectlyFinalizedBlockHashesList_asB64()
      );
      finalizedBlocks.add(directFinalizedBlockHash);

      let updatedLastFinalizedBlock = false;
      this.blocks?.forEach(block => {
        let bh = block.getSummary()!.getBlockHash_asB64();
        if (finalizedBlocks.has(bh)) {
          block.getStatus()?.setFinality(BlockInfo.Status.Finality.FINALIZED);
        } else if (orphanedBlocks.has(bh)) {
          block.getStatus()?.setFinality(BlockInfo.Status.Finality.ORPHANED);
        }
        if (!updatedLastFinalizedBlock && bh === directFinalizedBlockHash) {
          this.lastFinalizedBlock = block;
          updatedLastFinalizedBlock = true;
        }
      });
      if (!updatedLastFinalizedBlock) {
        this.errors.capture(
          this.casperService.getBlockInfo(event.getNewFinalizedBlock()!.getBlockHash()).then(block => {
            this.lastFinalizedBlock = block;
          })
        );
      }
    }
  }

  async refreshBlockDagAndSetupSubscriber() {
    await this.refreshBlockDag();
    this.toggleableSubscriber.setUpSubscriber();
  }

  private async refreshBlockDag() {
    // todo: (ECO-399) Use a more elegant loading style to indicate it is loading
    // or maybe spin the loading button so that the user can know it is refreshing.
    await this.errors.capture(
      this.casperService
        .getBlockInfos(this.depth, this.maxRank)
        .then(blocks => {
          this.blocks = observable.array(blocks);
        })
    );

    await this.errors.capture(
      this.casperService.getLastFinalizedBlockInfo().then(block => {
        this.lastFinalizedBlock = block;
      })
    );
  }
}

export default DagContainer;
